package dev.hartforge.foundryadditions.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * disk io for the session dir. every write is a temp file plus
 * Files.move(ATOMIC_MOVE) in the same dir: a crash costs at most the write
 * in flight, never a truncated doc. this is the exact durability the kubejs
 * sandbox could not offer (no rename primitive in there).
 *
 * the json trees are built by hand so that end:null is really emitted as
 * null (gson drops nulls by default) and so the afk key can stay absent.
 * both of those are signal to the downstream parser, not formatting.
 */
public final class SessionJson {

    /**
     * a missing file is doc null / failed false. anything unreadable is
     * failed true, and every caller then refuses to write, so a corrupt file
     * is never quietly replaced with a fresh empty one.
     */
    public record ReadResult(PlayerRecord doc, boolean failed) {}

    public record PresenceEntry(String uuid, String name) {}

    public static ReadResult read(Path path) {
        if (!Files.exists(path)) return new ReadResult(null, false);
        try {
            JsonObject o = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            PlayerRecord r = new PlayerRecord();
            r.uuid = optString(o, "uuid");
            r.name = optString(o, "name");
            if (o.has("names") && o.get("names").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("names")) {
                    JsonObject n = e.getAsJsonObject();
                    r.names.add(new PlayerRecord.NameSeen(
                            n.get("name").getAsString(), n.get("seen").getAsLong()));
                }
            }
            if (o.has("sessions") && o.get("sessions").isJsonArray()) {
                readSpans(o.getAsJsonArray("sessions"), r.sessions);
            }
            if (o.has("afk") && o.get("afk").isJsonArray()) {
                r.afk = new ArrayList<>();
                readSpans(o.getAsJsonArray("afk"), r.afk);
            }
            return new ReadResult(r, false);
        } catch (RuntimeException | IOException e) {
            return new ReadResult(null, true);
        }
    }

    private static void readSpans(JsonArray in, List<Span> out) {
        for (JsonElement e : in) {
            JsonObject s = e.getAsJsonObject();
            JsonElement end = s.get("end");
            out.add(new Span(
                    s.get("start").getAsLong(),
                    end == null || end.isJsonNull() ? null : end.getAsLong()));
        }
    }

    public static void write(Path path, PlayerRecord r) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("v", PlayerRecord.SCHEMA_VERSION);
        o.addProperty("uuid", r.uuid);
        o.addProperty("name", r.name);
        JsonArray names = new JsonArray();
        for (PlayerRecord.NameSeen n : r.names) {
            JsonObject e = new JsonObject();
            e.addProperty("name", n.name());
            e.addProperty("seen", n.seen());
            names.add(e);
        }
        o.add("names", names);
        o.add("sessions", spans(r.sessions));
        if (r.afk != null) o.add("afk", spans(r.afk));
        atomicWrite(path, o.toString());
    }

    private static JsonArray spans(List<Span> spans) {
        JsonArray arr = new JsonArray();
        for (Span s : spans) {
            JsonObject e = new JsonObject();
            e.addProperty("start", s.start);
            if (s.end == null) {
                e.add("end", JsonNull.INSTANCE);
            } else {
                e.addProperty("end", s.end);
            }
            arr.add(e);
        }
        return arr;
    }

    /** the era anchor: { era:[{id,start}] }. written once, never rewritten. */
    public static void writeMeta(Path path, String eraId, long startMs) throws IOException {
        JsonObject era = new JsonObject();
        era.addProperty("id", eraId);
        era.addProperty("start", startMs);
        JsonArray arr = new JsonArray();
        arr.add(era);
        JsonObject o = new JsonObject();
        o.add("era", arr);
        atomicWrite(path, o.toString());
    }

    /**
     * the presence snapshot: { ts, players:[{uuid,name}] }. a snapshot, not
     * a log, always safe to overwrite; ts is the freshness signal that lets
     * the site split a live open session from a crash orphan.
     */
    public static void writePresence(Path path, long ts, List<PresenceEntry> players)
            throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("ts", ts);
        JsonArray arr = new JsonArray();
        for (PresenceEntry p : players) {
            JsonObject e = new JsonObject();
            e.addProperty("uuid", p.uuid());
            e.addProperty("name", p.name());
            arr.add(e);
        }
        o.add("players", arr);
        atomicWrite(path, o.toString());
    }

    /**
     * the temp name starts with a dot and ends in .tmp, so the site's dir
     * scan (endsWith .json, not startsWith _) can never pick one up.
     */
    static void atomicWrite(Path path, String body) throws IOException {
        Path tmp = path.resolveSibling("." + path.getFileName() + ".tmp");
        Files.writeString(tmp, body, StandardCharsets.UTF_8);
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String optString(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private SessionJson() {}
}
