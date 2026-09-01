package dev.hartforge.foundryadditions.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.hartforge.foundryadditions.FoundryAdditions;
import dev.hartforge.foundryadditions.FoundryConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * the minecraft-facing edge of the session recorder. everything here runs on
 * the server main thread; the logic lives in SessionStore / AfkTracker /
 * PlayerRecord where the junit suite can reach it without a rig.
 *
 * one heartbeat every 1200 ticks: 60s at 20 tps, drifting long when the
 * server lags, which is the right direction (a server too slow to tick is
 * not one where somebody is quietly playing). unlike the kubejs logger there
 * is no reseed dance here: a mod does not get /reload-wiped, so presence
 * freshness and the afk sampler are the heartbeat's whole job.
 */
public final class SessionEvents {

    public static final int HEARTBEAT_TICKS = 1200;

    private static SessionStore store;
    private static AfkTracker tracker;

    private SessionEvents() {}

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        if (!FoundryConfig.SESSIONS_ENABLED.get()) {
            FoundryAdditions.LOGGER.info("session recorder disabled by config");
            return;
        }
        store = new SessionStore(
                FMLPaths.CONFIGDIR.get().resolve("foundry-additions").resolve("sessions"),
                FoundryConfig.SESSIONS_ERA_ID.get());
        tracker = FoundryConfig.AFK_ENABLED.get() ? new AfkTracker() : null;
        if (tracker != null) {
            AfkBridge.init();
        }
    }

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (store == null || !(event.getEntity() instanceof ServerPlayer player)) return;
        try {
            store.onJoin(player.getUUID(), player.getGameProfile().getName(), System.currentTimeMillis());
            // a new session starts with no afk history; anything left in the
            // sampler is from the session that just ended or from a crash
            if (tracker != null) tracker.remove(player.getUUID());
        } catch (RuntimeException e) {
            // never throw into the event. a broken session log must not break joining.
            FoundryAdditions.LOGGER.error("session log failed on join", e);
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (store == null || !(event.getEntity() instanceof ServerPlayer player)) return;
        try {
            long now = System.currentTimeMillis();
            store.onLeave(player.getUUID(), player.getGameProfile().getName(), now, "logout");
            if (tracker != null) {
                if (tracker.isAfk(player.getUUID())) {
                    AfkBridge.setAfk(player.getUUID(), false, player.getServer());
                }
                tracker.remove(player.getUUID());
            }
        } catch (RuntimeException e) {
            FoundryAdditions.LOGGER.error("session log failed on leave", e);
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (store == null) return;
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % HEARTBEAT_TICKS != 0) return;
        long now = System.currentTimeMillis();
        try {
            if (tracker != null) {
                sampleAfk(server, now);
            }
            // presence goes last, after the session and afk writes, so the
            // file that says "on right now" is never ahead of the files the
            // site actually counts hours from
            store.writePresence(now);
        } catch (RuntimeException e) {
            FoundryAdditions.LOGGER.error("session heartbeat failed", e);
        }
    }

    private static void sampleAfk(MinecraftServer server, long now) {
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            UUID uuid = p.getUUID();
            online.add(uuid);
            String pose = AfkTracker.poseKey(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
            AfkTracker.Result r = tracker.sample(uuid, pose, now);
            String name = p.getGameProfile().getName();
            switch (r.transition()) {
                case WENT_AFK -> {
                    store.openAfk(uuid, name, r.afkStartMs());
                    AfkBridge.setAfk(uuid, true, server);
                }
                case CAME_BACK -> {
                    store.closeAfk(uuid, name, now);
                    AfkBridge.setAfk(uuid, false, server);
                }
                case NONE -> { }
            }
        }
        tracker.retainAll(online);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        if (store != null) {
            store.flushAll(System.currentTimeMillis());
        }
        store = null;
        tracker = null;
    }

    /** the sessions verb payload. main thread only; the bridge guarantees it. */
    public static JsonArray snapshot() {
        JsonArray arr = new JsonArray();
        if (store == null) return arr;
        for (SessionStore.OpenSession s : store.snapshot()) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", s.uuid().toString().toLowerCase());
            o.addProperty("name", s.name());
            o.addProperty("start", s.startMs());
            o.addProperty("afk", tracker != null && tracker.isAfk(s.uuid()));
            arr.add(o);
        }
        return arr;
    }
}
