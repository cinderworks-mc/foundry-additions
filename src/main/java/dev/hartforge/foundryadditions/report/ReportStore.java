package dev.hartforge.foundryadditions.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ReportStore {

    public static final int CAP = 500;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    public ReportStore(Path file) {
        this.file = file;
    }

    public record Report(long ms, String kind, String uuid, String name, String pack, String dim,
                         int x, int y, int z, String held, String looking, String item, int recipes, String text) {}

    // an unreadable file means no write, same rule as the session log: never overwrite what we could not parse
    public synchronized void append(Report r) throws IOException {
        JsonObject doc = read();
        JsonArray list = doc.getAsJsonArray("reports");
        list.add(GSON.toJsonTree(r));
        while (list.size() > CAP) list.remove(0);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(doc), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private JsonObject read() throws IOException {
        if (!Files.exists(file)) {
            JsonObject fresh = new JsonObject();
            fresh.addProperty("v", 1);
            fresh.add("reports", new JsonArray());
            return fresh;
        }
        try {
            JsonObject doc = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!doc.has("reports") || !doc.get("reports").isJsonArray()) {
                throw new IOException("reports.json has no reports array");
            }
            return doc;
        } catch (RuntimeException e) {
            throw new IOException("reports.json unreadable", e);
        }
    }
}
