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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class DownloadsScanner {

    public static final Duration MAX_AGE = Duration.ofHours(24);

    public enum MoveResult { MOVED, COPIED_SOURCE_KEPT, ALREADY_EXISTS, REFUSED, FAILED }

    public enum RetireResult { RETIRED, REFUSED, FAILED }

    public record Want(String modId, String jarHint, String minBuild, String oldFile) {}

    // jar is null when nothing usable was in downloads
    public record Outcome(String modId, Path jar, MoveResult move, RetireResult retired) {}

    private DownloadsScanner() {}

    public static Path defaultDownloadsDir() {
        return Paths.get(System.getProperty("user.home", "."), "Downloads");
    }

    public static Optional<Path> find(Path downloadsDir, String jarHint, String minBuild, Instant now) {
        if (jarHint.isBlank() || !Files.isDirectory(downloadsDir)) return Optional.empty();
        String hint = jarHint.toLowerCase(Locale.ROOT);
        Path best = null;
        Instant bestTime = null;
        String bestBuild = "";
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(downloadsDir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".jar") || !name.startsWith(hint)) continue;
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;
                Instant mtime = Files.getLastModifiedTime(p).toInstant();
                // a little slack for clock skew, nothing from the future beyond that
                if (mtime.isBefore(now.minus(MAX_AGE)) || mtime.isAfter(now.plusSeconds(300))) continue;
                if (ExtrasChecker.buildTooOld(p.getFileName().toString(), minBuild)) continue;
                String build = ExtrasChecker.buildOf(p.getFileName().toString());
                // higher build first, mtime only when the builds tie or one won't parse
                int byBuild = build.isEmpty() || bestBuild.isEmpty() ? 0 : Versions.compare(build, bestBuild);
                if (best == null || byBuild > 0 || (byBuild == 0 && mtime.isAfter(bestTime))) {
                    best = p;
                    bestTime = mtime;
                    bestBuild = build;
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

    // rename only, never delete. neoforge skips non-.jar files so .old is inert
    public static RetireResult retireOld(Path modsDir, String oldFileName) {
        Path dir = modsDir.toAbsolutePath().normalize();
        Path old = dir.resolve(oldFileName).normalize();
        if (!dir.equals(old.getParent()) || !oldFileName.endsWith(".jar")) return RetireResult.REFUSED;
        if (!Files.isRegularFile(old, LinkOption.NOFOLLOW_LINKS)) return RetireResult.REFUSED;
        Path aside = dir.resolve(oldFileName + ".old");
        if (Files.exists(aside, LinkOption.NOFOLLOW_LINKS)) return RetireResult.REFUSED;
        try {
            Files.move(old, aside);
        } catch (IOException e) {
            return RetireResult.FAILED;
        }
        return RetireResult.RETIRED;
    }

    public static List<Outcome> installAll(Path downloadsDir, Path modsDir, List<Want> wants, Instant now) {
        List<Outcome> out = new ArrayList<>();
        for (Want w : wants) {
            Optional<Path> hit = find(downloadsDir, w.jarHint(), w.minBuild(), now);
            if (hit.isEmpty()) {
                out.add(new Outcome(w.modId(), null, null, null));
                continue;
            }
            Path jar = hit.get();
            MoveResult res = moveIntoMods(downloadsDir, jar, modsDir);
            boolean landed = res == MoveResult.MOVED || res == MoveResult.COPIED_SOURCE_KEPT;
            // two jars of one mod kills the launch, so the old one steps aside
            RetireResult retired = landed && !w.oldFile().isEmpty()
                    && !w.oldFile().equals(jar.getFileName().toString())
                    ? retireOld(modsDir, w.oldFile()) : null;
            out.add(new Outcome(w.modId(), jar, res, retired));
        }
        return out;
    }
}
