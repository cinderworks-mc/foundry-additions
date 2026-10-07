package dev.hartforge.foundryadditions.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadsScannerTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    private static Path jar(Path dir, String name, Instant mtime, String body) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, body);
        Files.setLastModifiedTime(p, FileTime.from(mtime));
        return p;
    }

    @Test
    void matchesHintCaseInsensitivelyAndPicksNewest(@TempDir Path dl) throws IOException {
        jar(dl, "astralsorcery-1.21-2.0.1.jar", NOW.minusSeconds(7200), "old");
        Path newer = jar(dl, "AstralSorcery-1.21-2.0.2.jar", NOW.minusSeconds(60), "new");
        jar(dl, "other-mod.jar", NOW, "x");
        assertEquals(Optional.of(newer), DownloadsScanner.find(dl, "AstralSorcery-", NOW));
        assertEquals(Optional.of(newer), DownloadsScanner.find(dl, "astralsorcery-", NOW));
    }

    @Test
    void ignoresOldFilesNonJarsAndPrefixMisses(@TempDir Path dl) throws IOException {
        jar(dl, "AstralSorcery-old.jar", NOW.minus(DownloadsScanner.MAX_AGE).minusSeconds(60), "x");
        jar(dl, "AstralSorcery-2.0.2.zip", NOW, "x");
        jar(dl, "my-AstralSorcery-2.0.2.jar", NOW, "x");
        assertTrue(DownloadsScanner.find(dl, "AstralSorcery-", NOW).isEmpty());
    }

    @Test
    void missingDirAndEmptyHintAreQuiet(@TempDir Path dl) {
        assertTrue(DownloadsScanner.find(dl.resolve("nope"), "x-", NOW).isEmpty());
        assertTrue(DownloadsScanner.find(dl, "", NOW).isEmpty());
    }

    @Test
    void moveCopiesThenDeletesSource(@TempDir Path root) throws IOException {
        Path dl = Files.createDirectory(root.resolve("dl"));
        Path mods = Files.createDirectory(root.resolve("mods"));
        Path src = jar(dl, "AstralSorcery-2.0.2.jar", NOW, "payload");
        assertEquals(DownloadsScanner.MoveResult.MOVED, DownloadsScanner.moveIntoMods(dl, src, mods));
        assertEquals("payload", Files.readString(mods.resolve("AstralSorcery-2.0.2.jar")));
        assertFalse(Files.exists(src));
        try (var s = Files.list(mods)) {
            assertEquals(1, s.count()); // no .part left behind
        }
    }

    @Test
    void neverOverwritesAndKeepsSource(@TempDir Path root) throws IOException {
        Path dl = Files.createDirectory(root.resolve("dl"));
        Path mods = Files.createDirectory(root.resolve("mods"));
        Path src = jar(dl, "a-1.jar", NOW, "new");
        Files.writeString(mods.resolve("a-1.jar"), "existing");
        assertEquals(DownloadsScanner.MoveResult.ALREADY_EXISTS, DownloadsScanner.moveIntoMods(dl, src, mods));
        assertEquals("existing", Files.readString(mods.resolve("a-1.jar")));
        assertTrue(Files.exists(src));
    }

    @Test
    void refusesSourcesOutsideDownloads(@TempDir Path root) throws IOException {
        Path dl = Files.createDirectory(root.resolve("dl"));
        Path mods = Files.createDirectory(root.resolve("mods"));
        Path elsewhere = jar(root, "secret.jar", NOW, "x");
        assertEquals(DownloadsScanner.MoveResult.REFUSED, DownloadsScanner.moveIntoMods(dl, elsewhere, mods));
        Path traversal = dl.resolve("../secret.jar");
        assertEquals(DownloadsScanner.MoveResult.REFUSED, DownloadsScanner.moveIntoMods(dl, traversal, mods));
        assertTrue(Files.exists(elsewhere));
        assertFalse(Files.exists(mods.resolve("secret.jar")));
    }

    @Test
    void missingModsDirFailsWithoutDeleting(@TempDir Path root) throws IOException {
        Path dl = Files.createDirectory(root.resolve("dl"));
        Path src = jar(dl, "a-1.jar", NOW, "x");
        assertEquals(DownloadsScanner.MoveResult.FAILED,
                DownloadsScanner.moveIntoMods(dl, src, root.resolve("nomods")));
        assertTrue(Files.exists(src));
    }
}
