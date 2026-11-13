package dev.hartforge.foundryadditions.client;

import dev.hartforge.foundryadditions.FoundryAdditions;
import dev.hartforge.foundryadditions.FoundryConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Mod(value = FoundryAdditions.MOD_ID, dist = Dist.CLIENT)
public final class FoundryAdditionsClient {

    private boolean done;
    private ExtrasSession session;
    private ExtrasScreen showing;
    private CompletableFuture<List<ExtrasManifest.Extra>> manifest;

    public FoundryAdditionsClient() {
        NeoForge.EVENT_BUS.register(this);
    }

    // polling the title instead of an Opening hook, other mods stack screens over it.
    // distant horizons' update screen replaces ours, so it comes back until the player closes it
    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        if (done) return;
        if (showing != null && showing.closed()) {
            done = true;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TitleScreen title) || mc.getOverlay() != null) return;

        if (session == null) {
            if (manifest == null) {
                if (!FoundryConfig.EXTRAS_PROMPT.get()) {
                    FoundryAdditions.LOGGER.info("extras prompt disabled by config");
                    done = true;
                    return;
                }
                startLoad();
            }
            if (!manifest.isDone()) return;
            session = newSession(ExtrasChecker.check(manifest.join()));
            int needing = session.needingAction();
            FoundryAdditions.LOGGER.info("extras check: {} of {} need attention", needing, session.rows().size());
            if (needing == 0) {
                done = true;
                return;
            }
            session.probeSizes();
        }
        showing = new ExtrasScreen(title, session);
        mc.setScreen(showing);
    }

    private static ExtrasSession newSession(List<ExtrasChecker.Row> rows) {
        Path mods = FMLPaths.MODSDIR.get();
        ExtrasDownloader.sweep(mods);
        return new ExtrasSession(rows, new ExtrasDownloader(ExtrasDownloader.defaultClient()), mods,
                DownloadsScanner.defaultDownloadsDir(), pool(), ModList.get()::isLoaded, FoundryAdditions.LOGGER::info);
    }

    private static ExecutorService pool() {
        return Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "foundry-extras-download");
            t.setDaemon(true);
            return t;
        });
    }

    private void startLoad() {
        Path dir = FMLPaths.CONFIGDIR.get().resolve("foundry-additions");
        String url = FoundryConfig.EXTRAS_URL.get();
        manifest = CompletableFuture.supplyAsync(() -> ExtrasManifest.load(
                dir.resolve("extras-override.json"), ExtrasManifest.httpFetcher(url),
                dir.resolve("extras-cache.json"), ExtrasManifest::bundledText,
                FoundryAdditions.LOGGER::warn),
                r -> {
                    Thread t = new Thread(r, "foundry-extras-manifest");
                    t.setDaemon(true);
                    t.start();
                });
    }
}
