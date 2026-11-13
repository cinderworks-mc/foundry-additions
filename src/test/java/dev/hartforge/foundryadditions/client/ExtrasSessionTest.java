package dev.hartforge.foundryadditions.client;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtrasSessionTest {

    private static ExtrasManifest.Extra extra(String id, String name, String min, List<String> requires) {
        return new ExtrasManifest.Extra(id, name, min, id + "-", "https://www.curseforge.com/" + id,
                "https://www.curseforge.com/api/v1/mods/1/files/2/download", min, "", requires);
    }

    private static ExtrasSession session(Map<String, String> installed, ExtrasManifest.Extra... extras) {
        List<ExtrasChecker.Row> rows = ExtrasChecker.rows(List.of(extras), id -> Optional.ofNullable(installed.get(id))
                .map(v -> new ExtrasChecker.Installed(v, id + "-" + v + ".jar")));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.shutdown();
        return new ExtrasSession(rows, new ExtrasDownloader(ExtrasDownloader.defaultClient()), Path.of("mods"),
                Path.of("dl"), pool, installed::containsKey, s -> {});
    }

    @Test
    void rowsGetTheirFourStates() {
        ExtrasSession s = session(Map.of("twilight", "4.8", "gadgets", "1.3.8", "applied", "1.1"),
                extra("twilight", "Twilight", "4.8", List.of()),
                extra("gadgets", "Gadgets", "1.3.9", List.of()),
                extra("thaumaturge", "Thaumaturge", "1.0.6", List.of()),
                extra("applied", "Applied Thaumaturge", "1.1", List.of("thaumaturge")));
        Map<String, ExtrasChecker.Row> by = new java.util.HashMap<>();
        s.rows().forEach(r -> by.put(r.extra().modId(), r));
        assertEquals(ExtrasChecker.Status.INSTALLED, by.get("twilight").status());
        assertEquals(ExtrasChecker.Status.OUTDATED, by.get("gadgets").status());
        assertEquals("1.3.8", by.get("gadgets").have());
        assertEquals("1.3.9", by.get("gadgets").need());
        assertEquals(ExtrasChecker.Status.MISSING, by.get("thaumaturge").status());
        assertEquals(List.of("thaumaturge"), s.missingRequires(by.get("applied")));
        assertTrue(s.needsAction(by.get("applied")));
        assertFalse(s.needsAction(by.get("twilight")));
        assertEquals(3, s.needingAction());
    }

    @Test
    void installedRowsSinkBelowOnesNeedingWork() {
        ExtrasSession s = session(Map.of("a", "1"),
                extra("a", "A", "1", List.of()), extra("b", "B", "1", List.of()), extra("c", "C", "1", List.of()));
        assertEquals(List.of("b", "c", "a"), s.rows().stream().map(r -> r.extra().modId()).toList());
    }

    @Test
    void oneClickAlsoFetchesListedRequirements() {
        ExtrasSession s = session(Map.of(),
                extra("astral", "Astral Sorcery", "2", List.of("observerlib", "create")),
                extra("observerlib", "ObserverLib", "1", List.of()));
        ExtrasChecker.Row astral = s.rows().get(0);
        assertEquals(List.of("astral", "observerlib"),
                s.wantedFor(astral).stream().map(r -> r.extra().modId()).toList());
        // create isn't on the list and isn't loaded, so it shows by id and can't be fetched here
        assertEquals(List.of("observerlib", "create"), s.missingRequires(astral));
        assertEquals("ObserverLib", s.nameOf("observerlib"));
    }

    @Test
    void installedModWithMissingRequirementOffersJustTheRequirement() {
        ExtrasSession s = session(Map.of("applied", "1.1"),
                extra("applied", "Applied Thaumaturge", "1.1", List.of("thaumaturge")),
                extra("thaumaturge", "Thaumaturge", "1.0.6", List.of()));
        ExtrasChecker.Row applied = s.rows().stream().filter(r -> r.extra().modId().equals("applied")).findFirst().get();
        assertEquals(List.of("thaumaturge"), s.wantedFor(applied).stream().map(r -> r.extra().modId()).toList());
    }
}
