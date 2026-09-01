package dev.hartforge.foundryadditions.ops;

import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * workers parse only. every verb that reads game state builds a task,
 * submits it to the MinecraftServer executor and waits on a deadline.
 * missing the deadline returns server_unresponsive, the single most useful
 * answer the socket can give: a wedged main thread is exactly the state
 * process-level monitoring has been blind to.
 */
public final class MainThreadBridge {

    /** the main thread did not run the task inside the deadline. */
    public static final class ServerUnresponsiveException extends Exception {
        public ServerUnresponsiveException() {
            super("server_unresponsive");
        }
    }

    private final MinecraftServer server;

    public MainThreadBridge(MinecraftServer server) {
        this.server = server;
    }

    public <T> T call(Supplier<T> task) throws ServerUnresponsiveException {
        CompletableFuture<T> future = server.submit(task);
        try {
            return future.get(OpsProtocol.MAIN_THREAD_DEADLINE_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // the task stays queued; there is no safe cancel for work the
            // main thread may already be running. report and move on.
            throw new ServerUnresponsiveException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServerUnresponsiveException();
        } catch (ExecutionException e) {
            throw new RuntimeException("ops task failed on the main thread", e.getCause());
        }
    }
}
