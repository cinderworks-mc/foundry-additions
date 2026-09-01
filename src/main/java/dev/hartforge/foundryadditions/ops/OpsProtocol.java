package dev.hartforge.foundryadditions.ops;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * the wire contract, stated once: one bounded request line per connection,
 * reply immediately, close. no read-to-EOF, no multiplexing.
 *
 * request: a single newline-terminated line, either a bare verb ("status")
 * or a json object with a "verb" member ({"verb":"status"}).
 *
 * response: one minified json line. minified matters: kuma keyword monitors
 * match the minified body, so "ready":true carries no space, ever.
 */
public final class OpsProtocol {

    public static final int MAX_REQUEST_BYTES = 8 * 1024;
    public static final int MAX_RESPONSE_BYTES = 256 * 1024;
    public static final long READ_DEADLINE_MS = 3_000;
    public static final int MAX_CONCURRENT = 8;
    public static final int QUEUE_DEPTH = 16;
    public static final long MAIN_THREAD_DEADLINE_MS = 2_000;

    public static final Set<String> VERBS = Set.of("ready", "status", "list", "sessions", "tps");

    // gson's default output is minified, which is exactly what the wire wants
    private static final Gson GSON = new Gson();

    /** parses one request line into a verb, or null when the line is not one. */
    public static String parseVerb(String line) {
        if (line == null) return null;
        String s = line.strip();
        if (s.isEmpty()) return null;
        if (s.startsWith("{")) {
            try {
                JsonObject o = JsonParser.parseString(s).getAsJsonObject();
                if (!o.has("verb") || !o.get("verb").isJsonPrimitive()) return null;
                s = o.get("verb").getAsString().strip();
            } catch (RuntimeException e) {
                return null;
            }
        }
        s = s.toLowerCase(Locale.ROOT);
        return s.matches("[a-z_]{1,32}") ? s : null;
    }

    /** renders a response as one newline-terminated minified json line, size-capped. */
    public static byte[] render(JsonObject response) {
        byte[] out = (GSON.toJson(response) + "\n").getBytes(StandardCharsets.UTF_8);
        if (out.length > MAX_RESPONSE_BYTES) {
            // the replacement is tiny, so this recursion terminates immediately
            return render(error("response_too_large"));
        }
        return out;
    }

    public static JsonObject error(String code) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", code);
        return o;
    }

    public static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    private OpsProtocol() {}
}
