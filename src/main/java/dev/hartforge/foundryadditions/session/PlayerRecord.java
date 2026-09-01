package dev.hartforge.foundryadditions.session;

import java.util.ArrayList;
import java.util.List;

/**
 * one player's on-disk doc. the shape is the kubejs logger's, field for
 * field, because the shadow-write diff runs both dirs through playtime-web's
 * own parser and that parser is the referee:
 *
 *   { v, uuid, name, names:[{name,seen}],
 *     sessions:[{start,end|null}], afk:[{start,end|null}] }
 *
 * every timestamp epoch ms and utc by definition. `v` is the schema version
 * the plan requires; the parser ignores keys it does not know.
 *
 * rules carried over from foundry_sessions.js, verbatim in spirit:
 * - never invent an end nobody observed. a stale open session stays open.
 * - an afk span never outlives its session.
 * - the afk key is ABSENT until the first afk span, so a doc with no key
 *   reads downstream as "never sampled", same as the kubejs files from
 *   before the sampler existed.
 */
public final class PlayerRecord {

    public static final int SCHEMA_VERSION = 1;

    /**
     * defensive limit on span counts. a runaway appender costs one player's
     * file a refusal that is visible in the log, not unbounded disk and a
     * parser meltdown.
     */
    public static final int MAX_SPANS = 50_000;

    public record NameSeen(String name, long seen) {}

    public String uuid;
    public String name;
    public final List<NameSeen> names = new ArrayList<>();
    public final List<Span> sessions = new ArrayList<>();
    public List<Span> afk; // null == key absent on disk

    public static PlayerRecord fresh(String uuid, String name) {
        PlayerRecord r = new PlayerRecord();
        r.uuid = uuid;
        r.name = name;
        return r;
    }

    /** a name change keeps the uuid; names[] is append-only, oldest first. */
    public void noteName(String newName, long nowMs) {
        if (newName == null || newName.isEmpty()) return;
        this.name = newName;
        if (!names.isEmpty() && names.get(names.size() - 1).name().equals(newName)) return;
        names.add(new NameSeen(newName, nowMs));
    }

    public int lastOpenSession() {
        for (int i = sessions.size() - 1; i >= 0; i--) {
            if (sessions.get(i).open()) return i;
        }
        return -1;
    }

    public int lastOpenAfk() {
        if (afk == null) return -1;
        for (int i = afk.size() - 1; i >= 0; i--) {
            if (afk.get(i).open()) return i;
        }
        return -1;
    }

    /** appends an open session. returns false when the cap refuses it. */
    public boolean openSession(long nowMs) {
        if (sessions.size() >= MAX_SPANS) return false;
        sessions.add(new Span(nowMs, null));
        return true;
    }

    /**
     * closes the last open session, or records {fallbackStart, endMs} when
     * nothing is open on disk but a join time survived in memory. with
     * neither it records nothing: a start nobody observed is not a start,
     * and a guessed one is worse than a missing one.
     *
     * any open afk span closes at the same end, in the same doc, so the two
     * can never disagree about where the session stopped.
     */
    public boolean closeSession(long endMs, Long fallbackStart) {
        int i = lastOpenSession();
        if (i >= 0) {
            sessions.get(i).end = endMs;
        } else if (fallbackStart != null) {
            if (sessions.size() >= MAX_SPANS) return false;
            sessions.add(new Span(fallbackStart, endMs));
        } else {
            return false;
        }
        closeAfk(endMs);
        return true;
    }

    /**
     * opens an afk span back-dated to sinceMs, the heartbeat that FIRST saw
     * the unchanged pose, not the one five minutes later that proved it had
     * not changed. refuses a double-open and the cap.
     */
    public boolean openAfk(long sinceMs) {
        if (afk == null) afk = new ArrayList<>();
        if (lastOpenAfk() >= 0) return false;
        if (afk.size() >= MAX_SPANS) return false;
        afk.add(new Span(sinceMs, null));
        return true;
    }

    /**
     * closes the open afk span. a close at or before the start is a torn or
     * clock-skewed write rather than an interval, and the span is dropped
     * instead of stored inside out (same rule as the kubejs logger).
     */
    public boolean closeAfk(long endMs) {
        int i = lastOpenAfk();
        if (i < 0) return false;
        if (endMs <= afk.get(i).start) {
            afk.remove(i);
            return true;
        }
        afk.get(i).end = endMs;
        return true;
    }

    /**
     * the join-time repair for a SIGKILL orphan: an afk span left open
     * inside a session that DID get closed ends at that session's end, an
     * observed bound. if the host session is open too there is no observed
     * bound and both stay open; the site's generator already clips afk to
     * whatever end it settles on for the session.
     */
    public void closeDanglingAfk() {
        int a = lastOpenAfk();
        if (a < 0) return;
        long afkStart = afk.get(a).start;
        for (int j = sessions.size() - 1; j >= 0; j--) {
            Span s = sessions.get(j);
            if (!s.open() && s.start <= afkStart && s.end >= afkStart) {
                closeAfk(s.end);
                return;
            }
        }
    }
}
