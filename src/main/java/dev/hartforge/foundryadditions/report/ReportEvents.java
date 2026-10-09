package dev.hartforge.foundryadditions.report;

import dev.hartforge.foundryadditions.FoundryAdditions;
import dev.hartforge.foundryadditions.FoundryConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

public final class ReportEvents {

    private static final long COOLDOWN_MS = 5000;
    private static final int REMINDER_DELAY_TICKS = 100;
    private static final Map<UUID, Long> LAST = new HashMap<>();

    private static ReportStore store;
    private static String pack = "unknown";

    private ReportEvents() {}

    public static void init() {
        // same path the kubejs version used, so the reader tool keeps working
        store = new ReportStore(FMLPaths.GAMEDIR.get().resolve("kubejs/config/foundry_reports/reports.json"));
        pack = readPackVersion();
    }

    // highest priority so chat formatting mods cannot swallow the message before we see it
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onChat(ServerChatEvent event) {
        if (store == null || !FoundryConfig.REPORTS_ENABLED.get()) return;
        Optional<ReportText.Parsed> parsed = ReportText.parse(event.getRawText());
        if (parsed.isEmpty()) return;
        event.setCanceled(true);
        ServerPlayer player = event.getPlayer();
        try {
            handle(player, parsed.get());
        } catch (RuntimeException e) {
            FoundryAdditions.LOGGER.error("report handler failed", e);
            tell(player, "could not save that, tell an admin", ChatFormatting.RED);
        }
    }

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (store == null || !FoundryConfig.REPORTS_ENABLED.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        // a few seconds after login so it lands under the join noise instead of in it
        server.tell(new TickTask(server.getTickCount() + REMINDER_DELAY_TICKS, () -> {
            if (!player.hasDisconnected()) {
                tell(player, "found a bug or got an idea? type !log <what went wrong> or !idea <your idea> in chat", ChatFormatting.GRAY);
            }
        }));
    }

    private static void handle(ServerPlayer player, ReportText.Parsed p) {
        if (p.body().isEmpty()) {
            tell(player, ReportText.usage(p.kind()), ChatFormatting.GRAY);
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST.get(player.getUUID());
        if (last != null && now - last < COOLDOWN_MS) {
            tell(player, "slow down, try again in a few seconds", ChatFormatting.GRAY);
            return;
        }

        String held = itemId(player.getMainHandItem());
        String looking = lookingAt(player);
        String item = looking.isEmpty() ? held : looking;
        int recipes = p.kind().equals("bug") && !item.isEmpty() ? recipeCount(player.getServer(), item) : -2;

        BlockPos pos = player.blockPosition();
        try {
            store.append(new ReportStore.Report(now, p.kind(), player.getUUID().toString(), player.getGameProfile().getName(),
                    pack, player.level().dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(),
                    held, looking, item, recipes, p.body()));
        } catch (IOException e) {
            FoundryAdditions.LOGGER.error("report not saved", e);
            tell(player, "could not save that, tell an admin", ChatFormatting.RED);
            return;
        }
        LAST.put(player.getUUID(), now);
        FoundryAdditions.LOGGER.info("{} report from {}: {}", p.kind(), player.getGameProfile().getName(), p.body());

        if (recipes == 0) {
            tell(player, "logged. heads up: nothing in this pack crafts " + item
                    + ", it may be removed on purpose or come from loot or trading", ChatFormatting.GRAY);
        } else {
            tell(player, p.kind().equals("idea") ? "idea logged, thanks" : "logged, thanks", ChatFormatting.GRAY);
        }
    }

    private static String itemId(ItemStack stack) {
        if (stack.isEmpty()) return "";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String lookingAt(ServerPlayer player) {
        HitResult hit = player.pick(6.0, 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult bhr)) return "";
        BlockState state = player.level().getBlockState(bhr.getBlockPos());
        if (state.isAir()) return "";
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    // every recipe in the live manager whose result is this item. 40k recipes, fine for a once-in-a-while report
    private static int recipeCount(MinecraftServer server, String itemId) {
        if (server == null) return -1;
        try {
            int n = 0;
            var access = server.registryAccess();
            for (var holder : server.getRecipeManager().getRecipes()) {
                try {
                    ItemStack out = holder.value().getResultItem(access);
                    if (!out.isEmpty() && itemId.equals(itemId(out))) n++;
                } catch (RuntimeException ignored) {
                    // some modded recipe types throw on getResultItem, skip them
                }
            }
            return n;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static void tell(ServerPlayer player, String text, ChatFormatting color) {
        player.sendSystemMessage(Component.literal(text).withStyle(color));
    }

    private static String readPackVersion() {
        Path props = FMLPaths.GAMEDIR.get().resolve("config").resolve("foundry-pack.properties");
        if (!Files.exists(props)) return "unknown";
        try (InputStream in = Files.newInputStream(props)) {
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("pack", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }
}
