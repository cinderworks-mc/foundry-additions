package dev.hartforge.foundryadditions.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtrasCheckerTest {

    @Test
    void olderBuildIsTooOld() {
        assertTrue(ExtrasChecker.buildTooOld("AstralSorcery-2.0.1.20.jar", "2.0.1.31"));
    }

    @Test
    void newerBuildIsFine() {
        assertFalse(ExtrasChecker.buildTooOld("AstralSorcery-2.0.1.32.jar", "2.0.1.31"));
        assertFalse(ExtrasChecker.buildTooOld("AstralSorcery-2.0.1.31.jar", "2.0.1.31"));
    }

    @Test
    void lastRunWinsOverMinecraftVersion() {
        assertEquals("2.0.1", ExtrasChecker.buildOf("AstralSorcery-1.21.1-2.0.1.jar"));
        assertTrue(ExtrasChecker.buildTooOld("AstralSorcery-1.21.1-2.0.1.jar", "2.0.1.31"));
    }

    @Test
    void renamedJarIsNeverFlagged() {
        assertFalse(ExtrasChecker.buildTooOld("astral.jar", "2.0.1.31"));
    }

    @Test
    void emptyMinBuildIsFine() {
        assertFalse(ExtrasChecker.buildTooOld("AstralSorcery-2.0.1.20.jar", ""));
    }
}
