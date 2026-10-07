package dev.hartforge.foundryadditions.client;

import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ExtrasChecker {

    public enum Status { MISSING, OUTDATED }

    public record Problem(ExtrasManifest.Extra extra, Status status, String haveVersion, String need,
                         String oldFile) {}

    private static final Pattern DOTTED = Pattern.compile("\\d+(\\.\\d+)+");

    private ExtrasChecker() {}

    public static List<Problem> check(List<ExtrasManifest.Extra> extras) {
        ModList mods = ModList.get();
        List<Problem> out = new ArrayList<>();
        for (ExtrasManifest.Extra e : extras) {
            if (!mods.isLoaded(e.modId())) {
                out.add(new Problem(e, Status.MISSING, "", "", ""));
                continue;
            }
            String have = mods.getModContainerById(e.modId())
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("");
            String jar = mods.getModFileById(e.modId()).getFile().getFileName();
            if (!e.minVersion().isEmpty() && !Versions.atLeast(have, e.minVersion())) {
                out.add(new Problem(e, Status.OUTDATED, have, e.minVersion(), jar));
                continue;
            }
            if (!e.minBuild().isEmpty() && buildTooOld(jar, e.minBuild())) {
                out.add(new Problem(e, Status.OUTDATED, "build " + buildOf(jar), "build " + e.minBuild(), jar));
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
