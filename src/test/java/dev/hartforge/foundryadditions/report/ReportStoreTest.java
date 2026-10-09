package dev.hartforge.foundryadditions.report;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReportStoreTest {

    @TempDir
    Path dir;

    private static ReportStore.Report report(int n) {
        return new ReportStore.Report(1000L + n, "bug", "u", "patrickhere", "1.0.1", "minecraft:overworld",
                1, 64, 2, "create:wrench", "create:mechanical_press", "create:mechanical_press", 3, "text " + n);
    }

    @Test
    void createsTheFileAndAppends() throws Exception {
        Path p = dir.resolve("a/b/reports.json");
        ReportStore s = new ReportStore(p);
        s.append(report(1));
        s.append(report(2));
        JsonObject doc = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
        assertEquals(1, doc.get("v").getAsInt());
        assertEquals(2, doc.getAsJsonArray("reports").size());
        assertEquals("create:mechanical_press", doc.getAsJsonArray("reports").get(0).getAsJsonObject().get("looking").getAsString());
    }

    @Test
    void oldestFallOffPastTheCap() throws Exception {
        Path p = dir.resolve("reports.json");
        ReportStore s = new ReportStore(p);
        for (int i = 0; i < ReportStore.CAP + 5; i++) s.append(report(i));
        var list = JsonParser.parseString(Files.readString(p)).getAsJsonObject().getAsJsonArray("reports");
        assertEquals(ReportStore.CAP, list.size());
        assertEquals("text 5", list.get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void anUnreadableFileIsNeverOverwritten() throws Exception {
        Path p = dir.resolve("reports.json");
        Files.writeString(p, "{ this is not json");
        assertThrows(IOException.class, () -> new ReportStore(p).append(report(1)));
        assertEquals("{ this is not json", Files.readString(p));
    }
}
