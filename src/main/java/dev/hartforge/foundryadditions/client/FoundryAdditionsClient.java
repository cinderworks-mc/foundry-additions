package dev.hartforge.foundryadditions.client;

import dev.hartforge.foundryadditions.FoundryAdditions;
import dev.hartforge.foundryadditions.FoundryConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Mod(value = FoundryAdditions.MOD_ID, dist = Dist.CLIENT)
public final class FoundryAdditionsClient {

    private boolean shown;
    private final CompletableFuture<List<ExtrasManifest.Extra>> manifest;

    public FoundryAdditionsClient() {
        Path cache = FMLPaths.CONFIGDIR.get().resolve("foundry-additions").resolve("extras-cache.json");
        // TODO: this fetch runs even with extrasPrompt off, config isn't loaded yet in here
        manifest = CompletableFuture.supplyAsync(() -> ExtrasManifest.load(
                ExtrasManifest.httpFetcher(ExtrasManifest.REMOTE_URL), cache, ExtrasManifest::bundledText),
                r -> {
                    Thread t = new Thread(r, "foundry-extras-manifest");
                    t.setDaemon(true);
                    t.start();
                });
        NeoForge.EVENT_BUS.register(this);
    }

    // polling the title instead of an Opening hook, other mods stack screens over it
    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        if (shown) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TitleScreen title) || mc.getOverlay() != null) return;
        if (!manifest.isDone()) return;
        shown = true;

        if (!FoundryConfig.EXTRAS_PROMPT.get()) {
            FoundryAdditions.LOGGER.info("extras prompt disabled by config");
            return;
        }
        List<ExtrasChecker.Problem> problems = ExtrasChecker.check(manifest.join());
        FoundryAdditions.LOGGER.info("extras check: {} problem(s)", problems.size());
        if (!problems.isEmpty()) {
            mc.setScreen(new ExtrasScreen(title, problems));
        }
    }
}
