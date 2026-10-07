package dev.hartforge.foundryadditions;

import dev.hartforge.foundryadditions.client.ExtrasManifest;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * one toggle per feature, all default on. deliberate divergence from the
 * rlmixins convention: these features replace working kubejs behaviour, so
 * default-off would be a silent regression on deploy day.
 */
public final class FoundryConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue OPS_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> OPS_SOCKET_PATH;
    public static final ModConfigSpec.ConfigValue<String> OPS_SOCKET_GROUP;

    public static final ModConfigSpec.BooleanValue SESSIONS_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> SESSIONS_ERA_ID;

    public static final ModConfigSpec.BooleanValue AFK_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> AFK_RIFT_CLASS;

    public static final ModConfigSpec.BooleanValue CRASHDATA_ENABLED;

    public static final ModConfigSpec.BooleanValue EXTRAS_PROMPT;
    public static final ModConfigSpec.ConfigValue<String> EXTRAS_URL;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("ops");
        OPS_ENABLED = b
                .comment("the unix ops socket. typed read-only verbs, filesystem perms are the boundary.")
                .define("enabled", true);
        OPS_SOCKET_PATH = b
                .comment("socket path. the parent dir belongs to systemd tmpfiles, not to this mod.")
                .define("socketPath", "/run/minecraft/foundry-ops.sock");
        OPS_SOCKET_GROUP = b
                .comment("group for the socket file (0660 owner:<group>). a dedicated group holding",
                        "the minecraft user and the bridge user and nothing else.")
                .define("socketGroup", "foundry-ops");
        b.pop();

        b.push("sessions");
        SESSIONS_ENABLED = b
                .comment("per-player session json under config/foundry-additions/sessions/.",
                        "this milestone shadow-writes next to the kubejs logger and deletes nothing.")
                .define("enabled", true);
        SESSIONS_ERA_ID = b
                .comment("era anchor id written once to _meta.json. lets a world rebuild render",
                        "as a boundary instead of a reset.")
                .define("eraId", "foundry-draft-1");
        b.pop();

        b.push("afk");
        AFK_ENABLED = b
                .comment("the afk pose sampler. changes no vote by itself; it feeds rift_essentials'",
                        "afk set, and rift's own handling of that set is where vote semantics live.")
                .define("enabled", true);
        AFK_RIFT_CLASS = b
                .comment("fully qualified class name of rift_essentials' AfkCommand, reached by",
                        "reflection. empty keeps the bridge off.",
                        "checked against rift_essentials 1.0.0: public static isAfk(UUID) and",
                        "setAfk(UUID, boolean, MinecraftServer).")
                .define("riftAfkCommandClass", "org.voxelrift.essentials.command.AfkCommand");
        b.pop();

        b.push("crashdata");
        CRASHDATA_ENABLED = b
                .comment("stamp crash reports with config/foundry-pack.properties",
                        "(the pack build script writes that file from its own version).")
                .define("enabled", true);
        b.pop();

        b.push("client");
        EXTRAS_PROMPT = b
                .comment("client only: on the title screen, offer help installing the extra mods the",
                        "foundry needs that we can't ship. false turns the screen off.")
                .define("extrasPrompt", true);
        EXTRAS_URL = b
                .comment("where the extras list is fetched from. config/foundry-additions/extras-override.json",
                        "beats this when it exists.")
                .define("extrasUrl", ExtrasManifest.REMOTE_URL);
        b.pop();

        SPEC = b.build();
    }

    private FoundryConfig() {}
}
