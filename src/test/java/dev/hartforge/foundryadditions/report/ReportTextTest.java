package dev.hartforge.foundryadditions.report;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportTextTest {

    @Test
    void parsesLogAndIdea() {
        var log = ReportText.parse("!log create press recipe is not working").orElseThrow();
        assertEquals("bug", log.kind());
        assertEquals("create press recipe is not working", log.body());
        var idea = ReportText.parse("  !IDEA a bigger backpack ").orElseThrow();
        assertEquals("idea", idea.kind());
        assertEquals("a bigger backpack", idea.body());
    }

    @Test
    void bareCommandGivesEmptyBody() {
        assertEquals("", ReportText.parse("!log").orElseThrow().body());
        assertEquals("", ReportText.parse("!idea   ").orElseThrow().body());
    }

    @Test
    void ignoresEverythingElse() {
        assertTrue(ReportText.parse("hello !log").isEmpty());
        assertTrue(ReportText.parse("!login now").isEmpty());
        assertTrue(ReportText.parse("!logs").isEmpty());
        assertTrue(ReportText.parse("log something").isEmpty());
        assertTrue(ReportText.parse(null).isEmpty());
    }

    @Test
    void longBodiesAreCut() {
        String body = ReportText.parse("!log " + "x".repeat(500)).orElseThrow().body();
        assertEquals(ReportText.MAX_LEN, body.length());
    }
}
