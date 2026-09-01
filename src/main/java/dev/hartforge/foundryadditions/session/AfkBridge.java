package dev.hartforge.foundryadditions.session;

import dev.hartforge.foundryadditions.FoundryAdditions;
import dev.hartforge.foundryadditions.FoundryConfig;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * writes our detector's verdict into rift_essentials' afk set so there is
 * one source of truth. that set is also what takes afk players out of the
 * sleep vote and the /day family: the electorate semantics ride rift's own
 * handling of its set, not any code here.
 *
 * STUB, deliberately. the plan confirms the shape, quote: "isAfk(UUID) and
 * setAfk(UUID, boolean, MinecraftServer) are public statics on a static
 * Set<UUID>" on rift's AfkCommand, but the fully qualified class name is not
 * written down anywhere and must come from javap on the pinned
 * rift_essentials jar. until afk.riftAfkCommandClass is set in the config
 * the bridge stays off and says so once. no maven artifact exists for the
 * mod, so reflection behind a runtime ModList check is the whole
 * integration; there is nothing to compile against.
 */
public final class AfkBridge {

    private static Method setAfk;
    private static boolean active;
    private static boolean failedOnce;

    public static void init() {
        active = false;
        setAfk = null;
        failedOnce = false;
        if (!ModList.get().isLoaded("rift_essentials")) {
            FoundryAdditions.LOGGER.info("rift_essentials not loaded, afk bridge off");
            return;
        }
        String fqcn = FoundryConfig.AFK_RIFT_CLASS.get();
        if (fqcn.isEmpty()) {
            FoundryAdditions.LOGGER.warn(
                    "rift_essentials is loaded but afk.riftAfkCommandClass is empty; afk bridge off. "
                            + "javap the pinned jar for AfkCommand's fqcn and set it in the config.");
            return;
        }
        try {
            Class<?> cls = Class.forName(fqcn);
            setAfk = cls.getMethod("setAfk", UUID.class, boolean.class, MinecraftServer.class);
            active = true;
            FoundryAdditions.LOGGER.info("afk bridge wired to {}", fqcn);
        } catch (ReflectiveOperationException | RuntimeException e) {
            FoundryAdditions.LOGGER.error("afk bridge could not resolve {}; bridge off", fqcn, e);
        }
    }

    public static void setAfk(UUID uuid, boolean afk, MinecraftServer server) {
        if (!active) return;
        try {
            setAfk.invoke(null, uuid, afk, server);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!failedOnce) {
                failedOnce = true;
                FoundryAdditions.LOGGER.error("afk bridge call failed, bridge off for this run", e);
            }
            active = false;
        }
    }

    private AfkBridge() {}
}
