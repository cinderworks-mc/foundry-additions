package dev.hartforge.foundryadditions;

import dev.hartforge.foundryadditions.ops.OpsSocket;
import dev.hartforge.foundryadditions.pack.CrashData;
import dev.hartforge.foundryadditions.report.ReportEvents;
import dev.hartforge.foundryadditions.session.SessionEvents;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * the foundry's own mod. v0.1 is server-side plumbing only: the ops socket,
 * the session/afk recorder and the crash stamp. no registry entries, no
 * payloads, no data components, nothing a vanilla client has to know about,
 * which is proved by the vanilla-client join test on the rig rather than
 * claimed here.
 */
@Mod(FoundryAdditions.MOD_ID)
public final class FoundryAdditions {

    public static final String MOD_ID = "foundryadditions";
    public static final Logger LOGGER = LoggerFactory.getLogger("foundryadditions");

    private OpsSocket opsSocket;

    public FoundryAdditions(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, FoundryConfig.SPEC);
        // config values are loaded by common setup, so the crash stamp toggle
        // is readable there and not in this constructor
        modBus.addListener((FMLCommonSetupEvent e) -> e.enqueueWork(CrashData::register));
        NeoForge.EVENT_BUS.register(SessionEvents.class);
        NeoForge.EVENT_BUS.register(ReportEvents.class);
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        ReportEvents.init();
        if (!FoundryConfig.OPS_ENABLED.get()) {
            LOGGER.info("ops socket disabled by config");
            return;
        }
        opsSocket = new OpsSocket(event.getServer());
        opsSocket.start();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (opsSocket != null) {
            opsSocket.stop();
            opsSocket = null;
        }
    }
}
