package dev.hartforge.foundryadditions.pack;

import dev.hartforge.foundryadditions.FoundryConfig;
import net.neoforged.fml.CrashReportCallables;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.TreeMap;

/**
 * one crash-report line naming the pack build, so a report from the rig or
 * the box says which pack version produced it. the pack's build script
 * writes config/foundry-pack.properties from its own version, so the stamp
 * cannot drift from the deploy.
 *
 * OPEN ITEM carried from the plan, quote: "javap CrashReportCallables
 * against the pinned 21.1.248 jar first, the signature is old but the class
 * has moved packages before". this compiles against the moddev classpath;
 * the javap check on the box's actual jar still belongs to the rig pass
 * before deploy.
 */
public final class CrashData {

    public static void register() {
        if (!FoundryConfig.CRASHDATA_ENABLED.get()) return;
        CrashReportCallables.registerCrashCallable("The Foundry", CrashData::stamp);
    }

    /** read at crash time, not at boot, so the stamp is the file as it is now. */
    private static String stamp() {
        Path props = FMLPaths.GAMEDIR.get().resolve("config").resolve("foundry-pack.properties");
        if (!Files.exists(props)) {
            return "config/foundry-pack.properties missing (the pack build script writes it)";
        }
        try {
            Properties p = new Properties();
            p.load(new StringReader(Files.readString(props, StandardCharsets.UTF_8)));
            TreeMap<String, String> sorted = new TreeMap<>();
            for (String k : p.stringPropertyNames()) {
                sorted.put(k, p.getProperty(k));
            }
            StringBuilder out = new StringBuilder();
            sorted.forEach((k, v) -> {
                if (!out.isEmpty()) out.append(", ");
                out.append(k).append('=').append(v);
            });
            return out.isEmpty() ? "foundry-pack.properties is empty" : out.toString();
        } catch (IOException | RuntimeException e) {
            return "foundry-pack.properties unreadable: " + e;
        }
    }

    private CrashData() {}
}
