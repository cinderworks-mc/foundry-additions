package dev.hartforge.foundryadditions.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionJsonTest {

    @TempDir
    Path dir;

    @Test
    void roundTripPreservesNullEnd() throws Exception {
        PlayerRecord r = PlayerRecord.fresh("9ff1c2c7-0000-0000-0000-000000000000", "patrickhere");
        r.noteName("patrickhere", 5);
        r.openSession(100);
        r.openAfk(150);
        Path p = dir.resolve(r.uuid + ".json");
        SessionJson.write(p, r);

        String raw = Files.readString(p);
        // the contract: an open span writes end as a literal null, never omits it
        assertTrue(raw.contains("\"end\":null"), raw);
        assertTrue(raw.contains("\"v\":1"), raw);

        SessionJson.ReadResult back = SessionJson.read(p);
        assertFalse(back.failed());
        assertEquals("patrickhere", back.doc().name);
        assertEquals(1, back.doc().names.size());
        assertEquals(100, back.doc().sessions.get(0).start);
        assertNull(back.doc().sessions.get(0).end);
        assertEquals(150, back.doc().afk.get(0).start);
    }

    @Test
    void afkKeyOmittedWhenNeverSampled() throws Exception {
        PlayerRecord r = PlayerRecord.fresh("u1", "n");
        r.openSession(100);
        Path p = dir.resolve("u1.json");
        SessionJson.write(p, r);
        assertFalse(Files.readString(p).contains("\"afk\""));
        assertNull(SessionJson.read(p).doc().afk);
    }

    @Test
    void missingFileIsNotAFailure() {
        SessionJson.ReadResult r = SessionJson.read(dir.resolve("nope.json"));
        assertNull(r.doc());
        assertFalse(r.failed());
    }

    @Test
    void corruptFileIsAFailure() throws Exception {
        Path p = dir.resolve("bad.json");
        Files.writeString(p, "{ not json");
        SessionJson.ReadResult r = SessionJson.read(p);
        assertTrue(r.failed());
        assertNull(r.doc());
    }

    @Test
    void atomicWriteLeavesNoTempFileBehind() throws Exception {
        PlayerRecord r = PlayerRecord.fresh("u2", "n");
        r.openSession(1);
        SessionJson.write(dir.resolve("u2.json"), r);
        try (var files = Files.list(dir)) {
            assertTrue(files.allMatch(f -> f.getFileName().toString().equals("u2.json")));
        }
    }

    @Test
    void presenceAndMetaShapes() throws Exception {
        SessionJson.writePresence(dir.resolve("_online.json"), 1234,
                List.of(new SessionJson.PresenceEntry("u3", "somebody")));
        String online = Files.readString(dir.resolve("_online.json"));
        assertTrue(online.contains("\"ts\":1234"));
        assertTrue(online.contains("\"name\":\"somebody\""));

        SessionJson.writeMeta(dir.resolve("_meta.json"), "foundry-draft-1", 999);
        String meta = Files.readString(dir.resolve("_meta.json"));
        assertTrue(meta.contains("\"id\":\"foundry-draft-1\""));
        assertTrue(meta.contains("\"start\":999"));
    }
}
