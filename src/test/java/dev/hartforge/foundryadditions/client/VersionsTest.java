package dev.hartforge.foundryadditions.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionsTest {

    @Test
    void olderFourPartIsBelowNewerThreePart() {
        assertFalse(Versions.atLeast("2.0.1.20", "2.0.2"));
    }

    @Test
    void equalIsEnough() {
        assertTrue(Versions.atLeast("2.0.2", "2.0.2"));
        assertTrue(Versions.atLeast("2.0.2.0", "2.0.2"));
    }

    @Test
    void numericNotLexical() {
        assertTrue(Versions.atLeast("1.10.3", "1.9.9"));
        assertFalse(Versions.atLeast("1.9.9", "1.10.3"));
    }

    @Test
    void junkSuffixesAndPrefixesAreTolerated() {
        assertTrue(Versions.atLeast("v2.0.3-beta", "2.0.2"));
        assertTrue(Versions.atLeast("1.10.3+build.7", "1.10.3"));
    }

    @Test
    void emptyMinAcceptsAnything() {
        assertTrue(Versions.atLeast("0.0.1", ""));
        assertTrue(Versions.atLeast("", ""));
    }

    @Test
    void unparseableHaveIsZero() {
        assertFalse(Versions.atLeast("abc", "1.0"));
    }
}
