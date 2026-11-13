package dev.hartforge.foundryadditions.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExtrasTextTest {

    private static ExtrasChecker.Row row(ExtrasChecker.Status status, String have, String need, String download) {
        ExtrasManifest.Extra e = new ExtrasManifest.Extra("m", "Mod", "1.0", "mod-", "https://www.curseforge.com/x",
                download, "1.0", "", List.of());
        return new ExtrasChecker.Row(e, status, have, need, "");
    }

    @Test
    void sizesReadLikeAFileBrowser() {
        assertEquals("1 KB", ExtrasText.size(10));
        assertEquals("820 KB", ExtrasText.size(820 * 1024));
        assertEquals("1.4 MB", ExtrasText.size(1462148));
        assertEquals("26 MB", ExtrasText.size(27154121));
    }

    @Test
    void everyStateHasPlainWords() {
        ExtrasSession.Phase idle = ExtrasSession.Phase.IDLE;
        assertEquals("not installed", ExtrasText.state(row(ExtrasChecker.Status.MISSING, "", "", "https://d"),
                idle, 0, 0, "", List.of()).text());
        assertEquals("not installed, also needs ObserverLib",
                ExtrasText.state(row(ExtrasChecker.Status.MISSING, "", "", "https://d"), idle, 0, 0, "",
                        List.of("ObserverLib")).text());
        assertEquals("update needed: you have 1.3.8, need 1.3.9",
                ExtrasText.state(row(ExtrasChecker.Status.OUTDATED, "1.3.8", "1.3.9", "https://d"), idle, 0, 0, "",
                        List.of()).text());
        assertEquals("installed (2.0)", ExtrasText.state(row(ExtrasChecker.Status.INSTALLED, "2.0", "", "https://d"),
                idle, 0, 0, "", List.of()).text());
        assertEquals("installed, but it needs Thaumaturge to work",
                ExtrasText.state(row(ExtrasChecker.Status.INSTALLED, "2.0", "", "https://d"), idle, 0, 0, "",
                        List.of("Thaumaturge")).text());
        assertEquals("not installed. get it from its page",
                ExtrasText.state(row(ExtrasChecker.Status.MISSING, "", "", ""), idle, 0, 0, "", List.of()).text());
    }

    @Test
    void downloadStatesShowProgressAndRestart() {
        ExtrasChecker.Row r = row(ExtrasChecker.Status.MISSING, "", "", "https://d");
        assertEquals("downloading, 75% of 10 MB",
                ExtrasText.state(r, ExtrasSession.Phase.DOWNLOADING, 7864320, 10485760, "", List.of()).text());
        assertEquals("starting download", ExtrasText.state(r, ExtrasSession.Phase.DOWNLOADING, 0, -1, "",
                List.of()).text());
        assertEquals("downloading, 2.0 MB so far", ExtrasText.progress(2097152, -1));
        assertEquals("downloaded. restart the game to load it",
                ExtrasText.state(r, ExtrasSession.Phase.DONE, 0, 0, "", List.of()).text());
        assertEquals("failed: the connection dropped. try again",
                ExtrasText.state(r, ExtrasSession.Phase.FAILED, 0, 0, "the connection dropped. try again",
                        List.of()).text());
        assertEquals("retry", ExtrasText.action(r, ExtrasSession.Phase.FAILED, true));
        assertEquals("stop", ExtrasText.action(r, ExtrasSession.Phase.DOWNLOADING, true));
        assertEquals("update", ExtrasText.action(row(ExtrasChecker.Status.OUTDATED, "1", "2", "https://d"),
                ExtrasSession.Phase.IDLE, true));
        assertEquals("", ExtrasText.action(r, ExtrasSession.Phase.DONE, false));
    }

    @Test
    void downloadAllShowsTotalOnlyWhenEverySizeIsKnown() {
        assertEquals("download all (2, 3.0 MB)",
                ExtrasText.downloadAll(2, List.of(OptionalLong.of(1048576), OptionalLong.of(2097152))));
        assertEquals("download all (2)", ExtrasText.downloadAll(2, List.of(OptionalLong.of(1), OptionalLong.empty())));
    }

    @Test
    void buttonTipSaysWhatWillLand() {
        assertEquals("downloads Astral Sorcery and ObserverLib (17 MB) into your mods folder",
                ExtrasText.actionTip("download", List.of("Astral Sorcery", "ObserverLib"), OptionalLong.of(17 * 1048576L)));
        assertEquals("downloads Thaumaturge into your mods folder",
                ExtrasText.actionTip("download", List.of("Thaumaturge"), OptionalLong.empty()));
        assertEquals("stop this download", ExtrasText.actionTip("stop", List.of(), OptionalLong.empty()));
    }

    @Test
    void namesJoinNaturally() {
        assertEquals("A", ExtrasText.names(List.of("A")));
        assertEquals("A and B", ExtrasText.names(List.of("A", "B")));
        assertEquals("A, B and C", ExtrasText.names(List.of("A", "B", "C")));
    }
}
