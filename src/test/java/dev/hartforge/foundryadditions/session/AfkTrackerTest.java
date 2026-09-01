package dev.hartforge.foundryadditions.session;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AfkTrackerTest {

    private static final String POSE = AfkTracker.poseKey(1, 64, -20, 90, 0);
    private static final String OTHER = AfkTracker.poseKey(2, 64, -20, 90, 0);

    @Test
    void fiveUnchangedSamplesGoAfkBackdatedToTheFirst() {
        AfkTracker t = new AfkTracker();
        UUID u = UUID.randomUUID();
        assertEquals(AfkTracker.Transition.NONE, t.sample(u, POSE, 0).transition());
        AfkTracker.Result last = null;
        for (int i = 1; i <= AfkTracker.AFK_SAMPLES; i++) {
            last = t.sample(u, POSE, i * 60_000L);
        }
        assertEquals(AfkTracker.Transition.WENT_AFK, last.transition());
        assertEquals(0L, last.afkStartMs()); // back-dated, not shaved by five minutes
        assertTrue(t.isAfk(u));
    }

    @Test
    void fourSamplesAreNotEnough() {
        AfkTracker t = new AfkTracker();
        UUID u = UUID.randomUUID();
        t.sample(u, POSE, 0);
        for (int i = 1; i < AfkTracker.AFK_SAMPLES; i++) {
            assertEquals(AfkTracker.Transition.NONE, t.sample(u, POSE, i * 60_000L).transition());
        }
        assertFalse(t.isAfk(u));
    }

    @Test
    void movementComesBack() {
        AfkTracker t = new AfkTracker();
        UUID u = UUID.randomUUID();
        t.sample(u, POSE, 0);
        for (int i = 1; i <= AfkTracker.AFK_SAMPLES; i++) {
            t.sample(u, POSE, i * 60_000L);
        }
        assertTrue(t.isAfk(u));
        AfkTracker.Result r = t.sample(u, OTHER, 360_000L);
        assertEquals(AfkTracker.Transition.CAME_BACK, r.transition());
        assertFalse(t.isAfk(u));
    }

    @Test
    void movementBeforeTheVerdictJustResetsTheRun() {
        AfkTracker t = new AfkTracker();
        UUID u = UUID.randomUUID();
        t.sample(u, POSE, 0);
        t.sample(u, POSE, 60_000L);
        assertEquals(AfkTracker.Transition.NONE, t.sample(u, OTHER, 120_000L).transition());
        assertFalse(t.isAfk(u));
    }

    @Test
    void quantizationBelowOneCentimeterIsTheSamePose() {
        assertEquals(AfkTracker.poseKey(1.001, 64, 0, 90, 0), AfkTracker.poseKey(1.002, 64, 0, 90, 0));
    }

    @Test
    void aRealStepIsADifferentPose() {
        assertFalse(AfkTracker.poseKey(1.0, 64, 0, 90, 0).equals(AfkTracker.poseKey(1.5, 64, 0, 90, 0)));
    }

    @Test
    void nonFinitePoseIsEmptyAndIgnored() {
        assertEquals("", AfkTracker.poseKey(Double.NaN, 0, 0, 0, 0));
        AfkTracker t = new AfkTracker();
        UUID u = UUID.randomUUID();
        assertEquals(AfkTracker.Transition.NONE, t.sample(u, "", 0).transition());
        assertFalse(t.isAfk(u));
    }
}
