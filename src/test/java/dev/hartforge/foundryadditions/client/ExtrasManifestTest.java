package dev.hartforge.foundryadditions.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtrasManifestTest {

    private static final String GOOD = """
            {"version":1,"extras":[
              {"modId":"astralsorcery","name":"Astral Sorcery","minVersion":"2.0.2",
               "jarHint":"AstralSorcery-","page":"https://example.com/astral","requires":["observerlib"]},
              {"modId":"observerlib","name":"ObserverLib","minVersion":"1.10.3",
               "jarHint":"observerlib-","page":"https://example.com/obs"}
            ]}""";

    private static final String BUNDLED = """
            {"version":1,"extras":[{"modId":"bundled","name":"Bundled"}]}""";

    private static ExtrasManifest.Fetcher offline() {
        return () -> {
            throw new IOException("offline");
        };
    }

    @Test
    void parsesTheDesignSchema() {
        List<ExtrasManifest.Extra> l = ExtrasManifest.parse(GOOD);
        assertEquals(2, l.size());
        assertEquals("astralsorcery", l.get(0).modId());
        assertEquals(List.of("observerlib"), l.get(0).requires());
        assertEquals("AstralSorcery-", l.get(0).jarHint());
        assertEquals(List.of(), l.get(1).requires());
    }

    @Test
    void parsesDownloadAndFileVersion() {
        ExtrasManifest.Extra e = ExtrasManifest.parse("""
                {"version":1,"extras":[{"modId":"a","name":"A",
                 "page":"https://example.com/files/1","download":"https://example.com/download/1",
                 "fileVersion":"2.0.1"}]}""").get(0);
        assertEquals("https://example.com/download/1", e.download());
        assertEquals("https://example.com/files/1", e.page());
        assertEquals("2.0.1", e.fileVersion());
    }

    @Test
    void missingDownloadAndFileVersionAreEmpty() {
        ExtrasManifest.Extra e = ExtrasManifest.parse(GOOD).get(0);
        assertEquals("", e.download());
        assertEquals("", e.fileVersion());
    }

    @Test
    void nonHttpsDownloadIsDropped() {
        ExtrasManifest.Extra e = ExtrasManifest.parse("""
                {"version":1,"extras":[{"modId":"a","name":"A","page":"https://example.com/p",
                 "download":"http://example.com/d"}]}""").get(0);
        assertEquals("", e.download());
        assertEquals("https://example.com/p", e.page());
    }

    @Test
    void badEntriesAreSkippedAndNonHttpsPagesDropped() {
        List<ExtrasManifest.Extra> l = ExtrasManifest.parse("""
                {"version":1,"extras":[
                  {"name":"no id"}, 5, {"modId":"a","name":"A","page":"file:///etc/passwd"}]}""");
        assertEquals(1, l.size());
        assertEquals("", l.get(0).page());
    }

    @Test
    void malformedThrows() {
        assertThrows(IllegalArgumentException.class, () -> ExtrasManifest.parse("{nope"));
        assertThrows(IllegalArgumentException.class, () -> ExtrasManifest.parse("[]"));
        assertThrows(IllegalArgumentException.class, () -> ExtrasManifest.parse("{\"version\":1}"));
        assertThrows(IllegalArgumentException.class, () -> ExtrasManifest.parse("{\"version\":2,\"extras\":[]}"));
    }

    @Test
    void remoteWinsAndFillsTheCache(@TempDir Path dir) throws IOException {
        Path cache = dir.resolve("sub/cache.json");
        List<ExtrasManifest.Extra> l = ExtrasManifest.load(() -> GOOD, cache, () -> BUNDLED);
        assertEquals(2, l.size());
        assertTrue(Files.isRegularFile(cache));
        assertEquals(GOOD, Files.readString(cache));
    }

    @Test
    void offlineUsesTheCache(@TempDir Path dir) throws IOException {
        Path cache = dir.resolve("cache.json");
        Files.writeString(cache, GOOD);
        assertEquals(2, ExtrasManifest.load(offline(), cache, () -> BUNDLED).size());
    }

    @Test
    void offlineWithNoCacheUsesBundled(@TempDir Path dir) {
        List<ExtrasManifest.Extra> l = ExtrasManifest.load(offline(), dir.resolve("none.json"), () -> BUNDLED);
        assertEquals("bundled", l.get(0).modId());
    }

    @Test
    void garbageRemoteDoesNotPoisonTheCache(@TempDir Path dir) throws IOException {
        Path cache = dir.resolve("cache.json");
        Files.writeString(cache, GOOD);
        List<ExtrasManifest.Extra> l = ExtrasManifest.load(() -> "<html>captive portal</html>", cache, () -> BUNDLED);
        assertEquals(2, l.size());
        assertEquals(GOOD, Files.readString(cache));
    }

    @Test
    void corruptCacheFallsToBundled(@TempDir Path dir) throws IOException {
        Path cache = dir.resolve("cache.json");
        Files.writeString(cache, "{{{");
        assertEquals("bundled", ExtrasManifest.load(offline(), cache, () -> BUNDLED).get(0).modId());
    }

    @Test
    void everythingBrokenIsEmptyNotAnException(@TempDir Path dir) {
        assertEquals(List.of(), ExtrasManifest.load(offline(), dir.resolve("x.json"), () -> "garbage"));
    }

    @Test
    void theBundledResourceParses() {
        ExtrasManifest.parse(ExtrasManifest.bundledText());
    }
}
