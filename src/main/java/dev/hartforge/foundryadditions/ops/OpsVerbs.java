package dev.hartforge.foundryadditions.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.hartforge.foundryadditions.FoundryAdditions;
import dev.hartforge.foundryadditions.FoundryConfig;
import dev.hartforge.foundryadditions.session.SessionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;

/**
 * typed read-only verbs only: ready, status, list, sessions, tps. no generic
 * exec in v0.1; rcon and the tmux console stay for administration and are
 * not deprecated. every verb that reads game state runs on the main thread
 * through the bridge, so a wedged server answers server_unresponsive instead
 * of racing the game or lying.
 */
final class OpsVerbs {

    private final MinecraftServer server;
    private final MainThreadBridge bridge;
    private final OpsSocket socket;
    private final long startedWallMs = System.currentTimeMillis();

    OpsVerbs(MinecraftServer server, MainThreadBridge bridge, OpsSocket socket) {
        this.server = server;
        this.bridge = bridge;
        this.socket = socket;
    }

    JsonObject dispatch(String verb) {
        try {
            return switch (verb) {
                case "ready" -> ready();
                case "status" -> status();
                case "list" -> list();
                case "sessions" -> sessions();
                case "tps" -> tps();
                default -> OpsProtocol.error("unknown_verb");
            };
        } catch (MainThreadBridge.ServerUnresponsiveException e) {
            return OpsProtocol.error("server_unresponsive");
        } catch (RuntimeException e) {
            FoundryAdditions.LOGGER.error("ops verb {} failed", verb, e);
            return OpsProtocol.error("internal_error");
        }
    }

    /**
     * ready is a claim about the main thread now, not about an event that
     * once fired: a live round trip every call, on top of the recorded
     * post-bind round trip.
     */
    private JsonObject ready() throws MainThreadBridge.ServerUnresponsiveException {
        bridge.call(() -> Boolean.TRUE);
        JsonObject o = OpsProtocol.ok();
        o.addProperty("ready", socket.readyRoundTripDone());
        return o;
    }

    private JsonObject status() throws MainThreadBridge.ServerUnresponsiveException {
        return bridge.call(() -> {
            JsonObject o = OpsProtocol.ok();
            o.addProperty("ready", socket.readyRoundTripDone());
            o.addProperty("players", server.getPlayerList().getPlayerCount());
            addTickTimes(o);
            o.addProperty("uptimeSec", (System.currentTimeMillis() - startedWallMs) / 1000);
            return o;
        });
    }

    private JsonObject list() throws MainThreadBridge.ServerUnresponsiveException {
        return bridge.call(() -> {
            JsonObject o = OpsProtocol.ok();
            JsonArray players = new JsonArray();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                JsonObject e = new JsonObject();
                e.addProperty("uuid", p.getUUID().toString());
                e.addProperty("name", p.getGameProfile().getName());
                players.add(e);
            }
            o.add("players", players);
            return o;
        });
    }

    private JsonObject sessions() throws MainThreadBridge.ServerUnresponsiveException {
        if (!FoundryConfig.SESSIONS_ENABLED.get()) {
            return OpsProtocol.error("sessions_disabled");
        }
        return bridge.call(() -> {
            JsonObject o = OpsProtocol.ok();
            o.add("sessions", SessionEvents.snapshot());
            return o;
        });
    }

    private JsonObject tps() throws MainThreadBridge.ServerUnresponsiveException {
        return bridge.call(() -> {
            JsonObject o = OpsProtocol.ok();
            addTickTimes(o);
            return o;
        });
    }

    /**
     * getAverageTickTimeNanos / getTickTimesNanos exist on 1.21.1 (checked
     * against the published api during planning). the window is the server's
     * own rolling 100 ticks and the payload says so: there is no 1-minute
     * tps on this class and none is faked here.
     */
    private void addTickTimes(JsonObject o) {
        double msptAvg = server.getAverageTickTimeNanos() / 1.0e6;
        long[] times = server.getTickTimesNanos();
        long[] sorted = Arrays.copyOf(times, times.length);
        Arrays.sort(sorted);
        double msptP99 = sorted.length == 0 ? 0
                : sorted[Math.min(sorted.length - 1, Math.max(0, (int) Math.ceil(sorted.length * 0.99) - 1))] / 1.0e6;
        double tps = msptAvg <= 0 ? 20.0 : Math.min(20.0, 1000.0 / Math.max(msptAvg, 50.0));
        o.addProperty("tps", round1(tps));
        o.addProperty("msptAvg", round1(msptAvg));
        o.addProperty("msptP99", round1(msptP99));
        o.addProperty("window", "100t");
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
