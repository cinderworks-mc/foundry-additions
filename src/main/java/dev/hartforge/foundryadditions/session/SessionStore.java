package dev.hartforge.foundryadditions.session;

import dev.hartforge.foundryadditions.FoundryAdditions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * the durable contract: per-player json under config/foundry-additions/
 * sessions/ is the source of truth, kept outside world data on purpose. a
 * malformed doc costs one player's history and is visible in ls, not the
 * whole server's.
 *
 * this milestone is the shadow-write: foundry_sessions.js keeps running in
 * its own dir, this store writes a parallel one, and cutover happens only
 * after a normalized diff of both through playtime-web's own parser passes,
 * plus a deliberate kill -9 recovery test. nothing here deletes anything.
 *
 * threading: every method must run on the server main thread. the socket's
 * sessions verb arrives through the main-thread bridge, so there is exactly
 * one writer and no locks.
 */
public final class SessionStore {

    private record Open(String name, long startMs) {}

    public record OpenSession(UUID uuid, String name, long startMs) {}

    private final Path dir;
    private final String eraId;
    private final Map<UUID, Open> open = new LinkedHashMap<>();
    private boolean metaChecked;

    public SessionStore(Path dir, String eraId) {
        this.dir = dir;
        this.eraId = eraId;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            FoundryAdditions.LOGGER.error("could not create session dir {}", dir, e);
        }
    }

    private Path playerPath(UUID uuid) {
        return dir.resolve(uuid.toString().toLowerCase() + ".json");
    }

    public void onJoin(UUID uuid, String name, long nowMs) {
        ensureMeta(nowMs);
        open.put(uuid, new Open(name, nowMs));
        Path path = playerPath(uuid);
        SessionJson.ReadResult read = SessionJson.read(path);
        if (read.failed()) {
            // corrupt file: never quietly replace it with a fresh empty one.
            // presence stays right via the open map; the doc is left alone.
            FoundryAdditions.LOGGER.error("{} is online but {} is unreadable, presence only", name, path);
            writePresence(nowMs);
            return;
        }
        PlayerRecord doc = read.doc() != null
                ? read.doc()
                : PlayerRecord.fresh(uuid.toString().toLowerCase(), name);
        doc.uuid = uuid.toString().toLowerCase();
        doc.noteName(name, nowMs);
        int stale = doc.lastOpenSession();
        if (stale >= 0) {
            // a SIGKILL orphan. never invent the end it lost; the site's
            // generator decides what to do with an open span.
            FoundryAdditions.LOGGER.warn("{} had an unclosed session from {}, leaving it open",
                    name, doc.sessions.get(stale).start);
        }
        doc.closeDanglingAfk();
        if (!doc.openSession(nowMs)) {
            FoundryAdditions.LOGGER.error("{} hit the span cap, session not recorded", name);
        }
        writeDoc(path, doc, name);
        writePresence(nowMs);
    }

    public void onLeave(UUID uuid, String name, long nowMs, String why) {
        Open o = open.remove(uuid);
        Path path = playerPath(uuid);
        SessionJson.ReadResult read = SessionJson.read(path);
        if (read.failed()) {
            FoundryAdditions.LOGGER.error("could not close a session for {} ({}), file unreadable", name, why);
            writePresence(nowMs);
            return;
        }
        PlayerRecord doc = read.doc();
        if (doc == null) {
            FoundryAdditions.LOGGER.error("no session file to close for {} ({})", name, why);
            writePresence(nowMs);
            return;
        }
        doc.uuid = uuid.toString().toLowerCase();
        doc.noteName(name, nowMs);
        // closeSession also closes any open afk span in the same write, so
        // the session end and the afk end can never disagree.
        if (!doc.closeSession(nowMs, o == null ? null : o.startMs())) {
            FoundryAdditions.LOGGER.error(
                    "{} left ({}) with no open session on disk and no join time in memory, nothing recorded",
                    name, why);
        }
        writeDoc(path, doc, name);
        writePresence(nowMs);
    }

    /** afk transition in: back-dated to the heartbeat that first saw the pose. */
    public void openAfk(UUID uuid, String name, long sinceMs) {
        mutateDoc(uuid, name, "open an afk span", doc -> {
            if (!doc.openAfk(sinceMs)) {
                FoundryAdditions.LOGGER.warn(
                        "{} already has an open afk span on disk (or hit the cap), not opening another", name);
                return false;
            }
            return true;
        });
    }

    /**
     * afk transition out, on movement. logout and server stop do not come
     * through here: they close the span inside closeSession's own write.
     */
    public void closeAfk(UUID uuid, String name, long nowMs) {
        mutateDoc(uuid, name, "close an afk span", doc -> doc.closeAfk(nowMs));
    }

    private void mutateDoc(UUID uuid, String name, String what, Predicate<PlayerRecord> mutation) {
        Path path = playerPath(uuid);
        SessionJson.ReadResult read = SessionJson.read(path);
        if (read.failed() || read.doc() == null) {
            FoundryAdditions.LOGGER.error("no readable session file to {} in for {}", what, name);
            return;
        }
        PlayerRecord doc = read.doc();
        doc.uuid = uuid.toString().toLowerCase();
        if (mutation.test(doc)) {
            writeDoc(path, doc, name);
        }
    }

    /**
     * flush on server stop. a clean stop disconnects players first, so
     * onLeave normally closed everything and this finds nothing; it exists
     * for the cases where it does not. a SIGKILL still loses the open
     * session, which is the accepted cost and the rig's recovery test.
     */
    public void flushAll(long nowMs) {
        List<UUID> uuids = new ArrayList<>(open.keySet());
        for (UUID uuid : uuids) {
            Open o = open.get(uuid);
            onLeave(uuid, o.name(), nowMs, "server stop");
        }
        writePresence(nowMs);
        if (!uuids.isEmpty()) {
            FoundryAdditions.LOGGER.info("flushed {} open session(s) on server stop", uuids.size());
        }
    }

    public void writePresence(long nowMs) {
        List<SessionJson.PresenceEntry> players = new ArrayList<>();
        for (Map.Entry<UUID, Open> e : open.entrySet()) {
            players.add(new SessionJson.PresenceEntry(
                    e.getKey().toString().toLowerCase(), e.getValue().name()));
        }
        try {
            SessionJson.writePresence(dir.resolve("_online.json"), nowMs, players);
        } catch (IOException e) {
            FoundryAdditions.LOGGER.error("presence write failed", e);
        }
    }

    /** who is on right now, with join times, for the sessions verb. */
    public List<OpenSession> snapshot() {
        List<OpenSession> out = new ArrayList<>();
        for (Map.Entry<UUID, Open> e : open.entrySet()) {
            out.add(new OpenSession(e.getKey(), e.getValue().name(), e.getValue().startMs()));
        }
        return out;
    }

    /** the era anchor, written once and never rewritten. */
    private void ensureMeta(long nowMs) {
        if (metaChecked) return;
        metaChecked = true; // set before the write: a failure must not retry on every join
        Path meta = dir.resolve("_meta.json");
        if (Files.exists(meta)) return;
        try {
            SessionJson.writeMeta(meta, eraId, nowMs);
        } catch (IOException e) {
            FoundryAdditions.LOGGER.error("could not write the era marker to {}", meta, e);
        }
    }

    private void writeDoc(Path path, PlayerRecord doc, String name) {
        if (doc.uuid == null || doc.uuid.isEmpty()) {
            FoundryAdditions.LOGGER.error("refusing to write a doc with no uuid for {}", name);
            return;
        }
        try {
            SessionJson.write(path, doc);
        } catch (IOException e) {
            FoundryAdditions.LOGGER.error("write failed for {}", path, e);
        }
    }
}
