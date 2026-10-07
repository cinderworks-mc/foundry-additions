package dev.hartforge.foundryadditions.client;

import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;

public final class ExtrasChecker {

    public enum Status { MISSING, OUTDATED }

    public record Problem(ExtrasManifest.Extra extra, Status status, String haveVersion) {}

    private ExtrasChecker() {}

    public static List<Problem> check(List<ExtrasManifest.Extra> extras) {
        ModList mods = ModList.get();
        List<Problem> out = new ArrayList<>();
        for (ExtrasManifest.Extra e : extras) {
            if (!mods.isLoaded(e.modId())) {
                out.add(new Problem(e, Status.MISSING, ""));
                continue;
            }
            String have = mods.getModContainerById(e.modId())
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("");
            if (!e.minVersion().isEmpty() && !Versions.atLeast(have, e.minVersion())) {
                out.add(new Problem(e, Status.OUTDATED, have));
            }
        }
        return out;
    }
}
