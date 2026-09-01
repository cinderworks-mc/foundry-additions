package dev.hartforge.foundryadditions.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** span math: the rules the kubejs logger enforces, kept under test here. */
class PlayerRecordTest {

    @Test
    void openThenCloseSession() {
        PlayerRecord r = PlayerRecord.fresh("u", "n");
        assertTrue(r.openSession(100));
        assertTrue(r.closeSession(200, null));
        assertEquals(1, r.sessions.size());
        assertEquals(100, r.sessions.get(0).start);
        assertEquals(200, r.sessions.get(0).end);
    }

    @Test
    void staleOpenSessionIsLeftOpen() {
        PlayerRecord r = PlayerRecord.fresh("u", "n");
        r.openSession(100); // sigkill orphan
        r.openSession(500); // next boot's join
        assertEquals(2, r.sessions.size());
        assertNull(r.sessions.get(0).end); // never invent an end nobody observed
    }

    @Test
    void closeFallsBackToMemoryJoinTime() {
        PlayerRecord r = PlayerRecord.fresh("u", "n");
        assertTrue(r.closeSession(300, 250L));
        assertEquals(250, r.sessions.get(0).start);
        assertEquals(300, r.sessions.get(0).end);
    }

    @Test
    void closeWithNothingRecordsNothing() {
        PlayerRecord r = PlayerRecord.fresh("u", "n");
        assertFalse(r.closeSession(300, null));
        assertTrue(r.sessions.isEmpty());
    }

    @Test
    void afkKeyAbsentUntilFirstSpan() {
        PlayerRecord r = PlayerRecord.fresh("u", "n");
        r.openSession(0);
        assertNull(r.afk);
    }

    @Test
    void afkBackDatesToFirstUnchangedSample() {
        PlayerRecord r = PlayerRecord.fresh("u", "n");
        r.openSession(0);
        assertTrue(r.openAfk(60_000));
        assertEquals(60_000, r.afk.get(0).start);
        assertNull(r.afk.get(0).end);
    }

    @Test
    void doubleOpenAfkRefused() {
        PlayerRecord r = PlayerRecord.fresh("u", "n");
        r.openSession(0);
        assertTrue(r.openAfk(100));
        assertFalse(r.openAfk(200));
        assertEquals(1, r.afk.size());
    }

    @Test
    void insideOutAfkSpanIsDroppedNotStored() {
        PlayerRecord r = PlayerRecord.fresh("u", "n");
        r.openSession(0);
        r.openAfk(500);
        assertTrue(r.closeAfk(500)); // close at (or before) start: torn write, drop it
        assertTrue(r.afk.isEmpty());
    }

    @Test
    void sessionCloseAlsoClosesAfkAtTheSameEnd() {
        PlayerRecord r = PlayerRecord.fresh("u", "n");
        r.openSession(0);
        r.openAfk(100);
        r.closeSession(900, null);
        assertEquals(900, r.sessions.get(0).end);
        assertEquals(900, r.afk.get(0).end);
    }

    @Test
    void danglingAfkClosesAtClosedHostEnd() {
        PlayerRecord r = PlayerRecord.fresh("u", "n");
        r.openSession(0);
        r.openAfk(100);
        r.sessions.get(0).end = 800L; // host closed, afk span orphaned by a crash
        r.closeDanglingAfk();
        assertEquals(800, r.afk.get(0).end);
    }

    @Test
    void danglingAfkInsideOpenHostStaysOpen() {
        PlayerRecord r = PlayerRecord.fresh("u", "n");
        r.openSession(0);
        r.openAfk(100);
        r.closeDanglingAfk(); // no observed bound exists, so nothing is invented
        assertNull(r.afk.get(0).end);
    }

    @Test
    void nameHistoryAppendsOnChangeOnly() {
        PlayerRecord r = PlayerRecord.fresh("u", "old");
        r.noteName("old", 1);
        r.noteName("old", 2);
        r.noteName("new", 3);
        assertEquals(2, r.names.size());
        assertEquals("new", r.name);
        assertEquals("old", r.names.get(0).name());
    }

    @Test
    void spanCapRefuses() {
        PlayerRecord r = PlayerRecord.fresh("u", "n");
        for (int i = 0; i < PlayerRecord.MAX_SPANS; i++) {
            r.sessions.add(new Span(i, (long) i + 1));
        }
        assertFalse(r.openSession(999_999));
        assertEquals(PlayerRecord.MAX_SPANS, r.sessions.size());
    }
}
