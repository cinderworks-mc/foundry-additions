package dev.hartforge.foundryadditions.ops;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsProtocolTest {

    @Test
    void bareVerbParses() {
        assertEquals("status", OpsProtocol.parseVerb("status"));
        assertEquals("status", OpsProtocol.parseVerb("  STATUS \r"));
    }

    @Test
    void jsonVerbParses() {
        assertEquals("tps", OpsProtocol.parseVerb("{\"verb\":\"tps\"}"));
    }

    @Test
    void garbageIsNull() {
        assertNull(OpsProtocol.parseVerb(null));
        assertNull(OpsProtocol.parseVerb(""));
        assertNull(OpsProtocol.parseVerb("   "));
        assertNull(OpsProtocol.parseVerb("{ not json"));
        assertNull(OpsProtocol.parseVerb("{\"noverb\":1}"));
        assertNull(OpsProtocol.parseVerb("two words"));
        assertNull(OpsProtocol.parseVerb("x".repeat(64)));
    }

    @Test
    void errorRendersMinifiedWithTrailingNewline() {
        String out = new String(OpsProtocol.render(OpsProtocol.error("busy")), StandardCharsets.UTF_8);
        // minified matters: kuma keyword monitors match the minified body
        assertEquals("{\"ok\":false,\"error\":\"busy\"}\n", out);
    }

    @Test
    void oversizeResponseIsReplacedNotTruncated() {
        JsonObject big = OpsProtocol.ok();
        big.addProperty("blob", "y".repeat(OpsProtocol.MAX_RESPONSE_BYTES + 1));
        byte[] out = OpsProtocol.render(big);
        String s = new String(out, StandardCharsets.UTF_8);
        assertTrue(out.length <= OpsProtocol.MAX_RESPONSE_BYTES);
        assertTrue(s.contains("response_too_large"));
    }

    @Test
    void limitsMatchThePlan() {
        assertEquals(8 * 1024, OpsProtocol.MAX_REQUEST_BYTES);
        assertEquals(256 * 1024, OpsProtocol.MAX_RESPONSE_BYTES);
        assertEquals(3_000, OpsProtocol.READ_DEADLINE_MS);
        assertEquals(8, OpsProtocol.MAX_CONCURRENT);
        assertEquals(16, OpsProtocol.QUEUE_DEPTH);
        assertEquals(2_000, OpsProtocol.MAIN_THREAD_DEADLINE_MS);
        assertEquals(java.util.Set.of("ready", "status", "list", "sessions", "tps"), OpsProtocol.VERBS);
    }
}
