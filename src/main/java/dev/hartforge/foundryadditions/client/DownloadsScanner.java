package dev.hartforge.foundryadditions.client;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

public final class DownloadsScanner {

    public static final Duration MAX_AGE = Duration.ofHours(24);

    public enum MoveResult { MOVED, COPIED_SOURCE_KEPT, ALREADY_EXISTS, REFUSED, FAILED }

    private DownloadsScanner() {}

    public static Path defaultDownloadsDir() {
        return Paths.get(System.getProperty("user.home", "."), "Downloads");
    }

    public static Optional<Path> find(Path downloadsDir, String jarHint, Instant now) {
        if (jarHint.isBlank() || !Files.isDirectory(downloadsDir)) return Optional.empty();
        String hint = jarHint.toLowerCase(Locale.ROOT);
        Path best = null;
        Instant bestTime = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(downloadsDir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".jar") || !name.startsWith(hint)) continue;
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;
                Instant mtime = Files.getLastModifiedTime(p).toInstant();
                // a little slack for clock skew, nothing from the future beyond that
                if (mtime.isBefore(now.minus(MAX_AGE)) || mtime.isAfter(now.plusSeconds(300))) continue;
                if (bestTime == null || mtime.isAfter(bestTime)) {
                    best = p;
                    bestTime = mtime;
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.ofNullable(best);
    }

    public static MoveResult moveIntoMods(Path downloadsDir, Path source, Path modsDir) {
        Path dl = downloadsDir.toAbsolutePath().normalize();
        Path src = source.toAbsolutePath().normalize();
        // direct children of downloads only, so ../ tricks go nowhere
        if (!dl.equals(src.getParent())) return MoveResult.REFUSED;
        if (!Files.isRegularFile(src, LinkOption.NOFOLLOW_LINKS)) return MoveResult.REFUSED;
        if (!Files.isDirectory(modsDir)) return MoveResult.FAILED;

        String name = src.getFileName().toString();
        Path target = modsDir.resolve(name);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return MoveResult.ALREADY_EXISTS;

        // .part so a half copied jar never looks like a mod
        Path part = modsDir.resolve(name + ".part");
        if (Files.exists(part, LinkOption.NOFOLLOW_LINKS)) return MoveResult.FAILED;
        try {
            Files.copy(src, part);
            Files.move(part, target);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
            }
            return e instanceof FileAlreadyExistsException ? MoveResult.ALREADY_EXISTS : MoveResult.FAILED;
        }
        try {
            Files.delete(src);
        } catch (IOException e) {
            return MoveResult.COPIED_SOURCE_KEPT;
        }
        return MoveResult.MOVED;
    }
}
