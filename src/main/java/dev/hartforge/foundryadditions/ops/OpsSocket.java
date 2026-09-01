package dev.hartforge.foundryadditions.ops;

import dev.hartforge.foundryadditions.FoundryAdditions;
import dev.hartforge.foundryadditions.FoundryConfig;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * the jar's only listener, forever. it never opens a tcp port; anything
 * lan-facing lives in an external bridge process that can be firewalled and
 * restarted without touching the server.
 *
 * limits are the protocol's: 8 concurrent connections, queue depth 16,
 * over-limit answers busy. the socket file is 0660 with a dedicated group;
 * the parent dir (mode, ownership, stale-file cleanup) belongs to systemd
 * tmpfiles, not to this class.
 */
public final class OpsSocket {

    private final MinecraftServer server;
    private final MainThreadBridge bridge;
    private final OpsVerbs verbs;

    private ServerSocketChannel channel;
    private Path socketPath;
    private ThreadPoolExecutor pool;
    private ScheduledExecutorService deadlines;

    /** true only after a completed post-bind main-thread round trip. */
    private volatile boolean readyRoundTripDone;
    private volatile boolean stopping;

    public OpsSocket(MinecraftServer server) {
        this.server = server;
        this.bridge = new MainThreadBridge(server);
        this.verbs = new OpsVerbs(server, bridge, this);
    }

    boolean readyRoundTripDone() {
        return readyRoundTripDone;
    }

    public void start() {
        socketPath = Path.of(FoundryConfig.OPS_SOCKET_PATH.get());
        try {
            channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            channel.bind(UnixDomainSocketAddress.of(socketPath));
        } catch (IOException e) {
            // bind failure is ownership failure and we fail closed: log
            // ERROR, do not unlink, do not start the listener, the server
            // still boots. a probe-then-unlink race can delete the path of a
            // server that just started; cleanup is tmpfiles' job.
            FoundryAdditions.LOGGER.error(
                    "ops socket could not bind {} and is NOT listening. not unlinking anything; "
                            + "if the path is stale, systemd tmpfiles owns cleanup.",
                    socketPath, e);
            closeChannelQuietly();
            channel = null;
            return;
        }

        applyPermissions();

        deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "foundry-ops-deadline");
            t.setDaemon(true);
            return t;
        });
        pool = new ThreadPoolExecutor(
                OpsProtocol.MAX_CONCURRENT, OpsProtocol.MAX_CONCURRENT,
                30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(OpsProtocol.QUEUE_DEPTH),
                r -> {
                    Thread t = new Thread(r, "foundry-ops-worker");
                    t.setDaemon(true);
                    return t;
                });
        pool.allowCoreThreadTimeOut(true);

        Thread acceptor = new Thread(this::acceptLoop, "foundry-ops-accept");
        acceptor.setDaemon(true);
        acceptor.start();

        // the post-bind round trip that defines ready. "started event plus a
        // tick" proves neither that the listener bound nor that a request can
        // cross the main-thread bridge; this proves both halves.
        server.submit(() -> {
            readyRoundTripDone = true;
            FoundryAdditions.LOGGER.info(
                    "ops socket listening on {} (post-bind main-thread round trip complete)", socketPath);
        });
    }

    private void applyPermissions() {
        // 0660 owner:<group>. the owner is whoever the jvm runs as (the
        // minecraft user); the group is a dedicated one holding the minecraft
        // user and the bridge user and nothing else. failing either is loud
        // but not fatal: the tmpfiles-owned parent dir mode is the outer wall.
        try {
            Files.setPosixFilePermissions(socketPath, PosixFilePermissions.fromString("rw-rw----"));
        } catch (IOException | UnsupportedOperationException e) {
            FoundryAdditions.LOGGER.warn("could not chmod 660 on {}", socketPath, e);
        }
        String group = FoundryConfig.OPS_SOCKET_GROUP.get();
        if (!group.isEmpty()) {
            try {
                GroupPrincipal g = socketPath.getFileSystem().getUserPrincipalLookupService()
                        .lookupPrincipalByGroupName(group);
                Files.getFileAttributeView(socketPath, PosixFileAttributeView.class).setGroup(g);
            } catch (IOException | UnsupportedOperationException e) {
                FoundryAdditions.LOGGER.warn(
                        "could not set group {} on {} (does the group exist on this box?)",
                        group, socketPath, e);
            }
        }
    }

    private void acceptLoop() {
        while (!stopping) {
            SocketChannel client;
            try {
                client = channel.accept();
            } catch (ClosedChannelException e) {
                return; // stop() closed us
            } catch (IOException e) {
                if (!stopping) FoundryAdditions.LOGGER.error("ops socket accept failed", e);
                return;
            }
            try {
                pool.execute(new OpsConnection(client, verbs, deadlines));
            } catch (RejectedExecutionException e) {
                busy(client);
            }
        }
    }

    /**
     * over-limit gets busy. the reply is ~30 bytes, which fits any socket
     * send buffer, so this write cannot wedge the acceptor.
     */
    private static void busy(SocketChannel client) {
        try (client) {
            client.write(ByteBuffer.wrap(
                    "{\"ok\":false,\"error\":\"busy\"}\n".getBytes(StandardCharsets.UTF_8)));
        } catch (IOException ignored) {
        }
    }

    public void stop() {
        stopping = true;
        boolean wasBound = channel != null;
        closeChannelQuietly();
        if (pool != null) pool.shutdownNow();
        if (deadlines != null) deadlines.shutdownNow();
        if (wasBound && socketPath != null) {
            // we bound this path ourselves, so unlinking it on a clean stop
            // is ownership, not the probe-then-unlink race the plan forbids.
            try {
                Files.deleteIfExists(socketPath);
            } catch (IOException e) {
                FoundryAdditions.LOGGER.warn("could not remove {} on stop", socketPath, e);
            }
            FoundryAdditions.LOGGER.info("ops socket stopped");
        }
    }

    private void closeChannelQuietly() {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }
}
