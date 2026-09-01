package dev.hartforge.foundryadditions.session;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * the pose sampler, same semantics as the kubejs one so the shadow diff
 * compares like with like: one sample per 60s heartbeat, position quantized
 * to 1cm and rotation to a tenth of a degree, and a player whose sample has
 * not changed for 5 consecutive heartbeats is afk from the FIRST sample that
 * carried the unchanged value. both ends of a span are quantized to the
 * heartbeat; that is the honest resolution of a once-a-minute sampler and it
 * is not smoothed over here.
 *
 * pure java on purpose: the events layer reads the player and hands numbers
 * in, so the back-dating and the transition logic are unit-testable without
 * a rig.
 */
public final class AfkTracker {

    /** consecutive unchanged heartbeats before the verdict: 5 minutes at 20 tps. */
    public static final int AFK_SAMPLES = 5;

    public enum Transition { NONE, WENT_AFK, CAME_BACK }

    /** afkStartMs is meaningful only for WENT_AFK: the back-dated start. */
    public record Result(Transition transition, long afkStartMs) {
        static final Result NO_CHANGE = new Result(Transition.NONE, 0);
    }

    private static final class Sample {
        String pose;
        long sinceMs;
        int count;
        boolean afk;
    }

    private final Map<UUID, Sample> samples = new HashMap<>();

    /**
     * five numbers to one comparable key, or "" when any is not finite.
     * position quantum 1cm, rotation quantum 0.1 degree: tight enough that a
     * single step or a nudge of the mouse registers, loose enough that
     * bobbing in water does not read as playing.
     */
    public static String poseKey(double x, double y, double z, double yRot, double xRot) {
        double[] nums = {x, y, z, yRot, xRot};
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < nums.length; i++) {
            double v = nums[i];
            if (Double.isNaN(v) || Double.isInfinite(v)) return "";
            double q = i < 3 ? Math.round(v * 100) / 100.0 : Math.round(v * 10) / 10.0;
            if (i > 0) out.append('|');
            out.append(q);
        }
        return out.toString();
    }

    public Result sample(UUID uuid, String pose, long nowMs) {
        if (pose.isEmpty()) return Result.NO_CHANGE;
        Sample prev = samples.get(uuid);
        if (prev == null || !prev.pose.equals(pose)) {
            // they moved, or this is their first sample: a fresh run starts here
            boolean cameBack = prev != null && prev.afk;
            Sample s = new Sample();
            s.pose = pose;
            s.sinceMs = nowMs;
            samples.put(uuid, s);
            return cameBack ? new Result(Transition.CAME_BACK, 0) : Result.NO_CHANGE;
        }
        prev.count++;
        if (!prev.afk && prev.count >= AFK_SAMPLES) {
            prev.afk = true;
            // the span starts at sinceMs, the heartbeat that first saw this
            // pose, or every afk span silently loses its first five minutes
            return new Result(Transition.WENT_AFK, prev.sinceMs);
        }
        return Result.NO_CHANGE;
    }

    public boolean isAfk(UUID uuid) {
        Sample s = samples.get(uuid);
        return s != null && s.afk;
    }

    public void remove(UUID uuid) {
        samples.remove(uuid);
    }

    /** drops state for anyone no longer online, so the map cannot leak. */
    public void retainAll(Set<UUID> online) {
        samples.keySet().retainAll(online);
    }
}
