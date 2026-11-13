package dev.hartforge.foundryadditions.client;

import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ExtrasChecker {

    public enum Status { INSTALLED, OUTDATED, MISSING }

    public record Installed(String version, String fileName) {}

    // have/need are blank unless OUTDATED. oldFile is the loaded jar, set aside after an update
    public record Row(ExtrasManifest.Extra extra, Status status, String have, String need, String oldFile) {}

    private static final Pattern DOTTED = Pattern.compile("\\d+(\\.\\d+)+");

    private ExtrasChecker() {}

    public static List<Row> check(List<ExtrasManifest.Extra> extras) {
        ModList mods = ModList.get();
        return rows(extras, id -> {
            if (!mods.isLoaded(id)) return Optional.empty();
            String version = mods.getModContainerById(id)
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("");
            return Optional.of(new Installed(version, mods.getModFileById(id).getFile().getFileName()));
        });
    }

    public static List<Row> rows(List<ExtrasManifest.Extra> extras, Function<String, Optional<Installed>> lookup) {
        List<Row> out = new ArrayList<>();
        for (ExtrasManifest.Extra e : extras) {
            Optional<Installed> got = lookup.apply(e.modId());
            if (got.isEmpty()) {
                out.add(new Row(e, Status.MISSING, "", "", ""));
                continue;
            }
            String have = got.get().version();
            String jar = got.get().fileName();
            if (!e.minVersion().isEmpty() && !Versions.atLeast(have, e.minVersion())) {
                out.add(new Row(e, Status.OUTDATED, have, e.minVersion(), jar));
            } else if (!e.minBuild().isEmpty() && buildTooOld(jar, e.minBuild())) {
                out.add(new Row(e, Status.OUTDATED, buildOf(jar), e.minBuild(), jar));
            } else {
                out.add(new Row(e, Status.INSTALLED, have, "", jar));
            }
        }
        return out;
    }

    // some mods keep the same toml version across builds, only the jar name changes
    public static boolean buildTooOld(String fileName, String minBuild) {
        String build = buildOf(fileName);
        return !minBuild.isEmpty() && !build.isEmpty() && !Versions.atLeast(build, minBuild);
    }

    // last dotted run wins, the first one is usually the minecraft version
    public static String buildOf(String fileName) {
        Matcher m = DOTTED.matcher(fileName);
        String last = "";
        while (m.find()) last = m.group();
        return last;
    }
}
