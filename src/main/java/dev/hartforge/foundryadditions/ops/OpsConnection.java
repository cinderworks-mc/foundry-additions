package dev.hartforge.foundryadditions.ops;

import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * one connection, one bounded line, one reply, close. the read deadline is
 * enforced by closing the channel from a shared timer: unix socket channels
 * have no SO_TIMEOUT, and the close turns a blocked read into an exception.
 * that is the "socket held open with no data" rig test, answered.
 */
final class OpsConnection implements Runnable {

    private final SocketChannel client;
    private final OpsVerbs verbs;
    private final ScheduledExecutorService deadlines;

    OpsConnection(SocketChannel client, OpsVerbs verbs, ScheduledExecutorService deadlines) {
        this.client = client;
        this.verbs = verbs;
        this.deadlines = deadlines;
    }

    @Override
    public void run() {
        long started = System.nanoTime();
        String verb = null;
        ScheduledFuture<?> killer = deadlines.schedule(
                this::closeClient, OpsProtocol.READ_DEADLINE_MS, TimeUnit.MILLISECONDS);
        try {
            String line = readLine();
            killer.cancel(false);
            JsonObject response;
            if (line == null) {
                response = OpsProtocol.error("request_too_long");
            } else {
                verb = OpsProtocol.parseVerb(line);
                response = verb == null ? OpsProtocol.error("bad_request") : verbs.dispatch(verb);
            }
            writeFully(OpsProtocol.render(response));
            audit(verb, response, started);
        } catch (ClosedChannelException e) {
            OpsAudit.log(verb == null ? "-" : verb, false, "read_timeout", elapsedMs(started));
        } catch (IOException e) {
            OpsAudit.log(verb == null ? "-" : verb, false, "io_error", elapsedMs(started));
        } finally {
            killer.cancel(false);
            closeClient();
        }
    }

    /** reads up to one newline. returns null when the line exceeds the cap. */
    private String readLine() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        ByteBuffer chunk = ByteBuffer.allocate(512);
        while (true) {
            chunk.clear();
            int n = client.read(chunk);
            if (n < 0) break; // EOF without a newline: what arrived is the line
            chunk.flip();
            while (chunk.hasRemaining()) {
                byte b = chunk.get();
                if (b == '\n') return buf.toString(StandardCharsets.UTF_8);
                buf.write(b);
                if (buf.size() > OpsProtocol.MAX_REQUEST_BYTES) return null;
            }
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private void writeFully(byte[] out) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(out);
        while (buf.hasRemaining()) {
            client.write(buf);
        }
    }

    private void audit(String verb, JsonObject response, long startedNanos) {
        boolean ok = response.has("ok") && response.get("ok").getAsBoolean();
        String err = response.has("error") ? response.get("error").getAsString() : null;
        OpsAudit.log(verb == null ? "-" : verb, ok, err, elapsedMs(startedNanos));
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private void closeClient() {
        try {
            client.close();
        } catch (IOException ignored) {
        }
    }
}
