package dev.hartforge.foundryadditions.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class ExtrasManifest {

    public static final String REMOTE_URL = "https://cinderworks.dev/foundry/pack/extras.json";
    public static final String BUNDLED_RESOURCE = "/foundry-extras.json";
    public static final int TIMEOUT_MS = 3000;
    private static final int MAX_BYTES = 256 * 1024;

    public record Extra(String modId, String name, String minVersion, String jarHint,
                        String page, String download, String fileVersion,
                        List<String> requires) {}

    @FunctionalInterface
    public interface Fetcher {
        String fetch() throws IOException;
    }

    private ExtrasManifest() {}

    public static List<Extra> parse(String json) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("malformed manifest", e);
        }
        JsonElement version = root.get("version");
        if (version == null || !version.isJsonPrimitive() || version.getAsInt() != 1) {
            throw new IllegalArgumentException("unsupported manifest version");
        }
        if (!(root.get("extras") instanceof JsonArray extras)) {
            throw new IllegalArgumentException("manifest has no extras array");
        }
        List<Extra> out = new ArrayList<>();
        for (JsonElement e : extras) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String modId = str(o, "modId");
            String name = str(o, "name");
            if (modId.isEmpty() || name.isEmpty()) continue;
            String page = httpsOnly(str(o, "page"));
            String download = httpsOnly(str(o, "download"));
            List<String> requires = new ArrayList<>();
            if (o.get("requires") instanceof JsonArray arr) {
                for (JsonElement r : arr) {
                    if (r.isJsonPrimitive()) requires.add(r.getAsString());
                }
            }
            out.add(new Extra(modId, name, str(o, "minVersion"), str(o, "jarHint"), page, download,
                    str(o, "fileVersion"), List.copyOf(requires)));
        }
        return List.copyOf(out);
    }

    private static String httpsOnly(String url) {
        return url.regionMatches(true, 0, "https://", 0, 8) ? url : "";
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString().trim() : "";
    }

    public static List<Extra> load(Fetcher fetcher, Path cacheFile, Supplier<String> bundled) {
        try {
            String text = fetcher.fetch();
            List<Extra> parsed = parse(text);
            writeCache(cacheFile, text);
            return parsed;
        } catch (IOException | RuntimeException ignored) {
            // offline, timeout or junk, try the cache
        }
        try {
            if (Files.isRegularFile(cacheFile) && Files.size(cacheFile) <= MAX_BYTES) {
                return parse(Files.readString(cacheFile, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException ignored) {
        }
        try {
            return parse(bundled.get());
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static void writeCache(Path cacheFile, String text) {
        try {
            Files.createDirectories(cacheFile.getParent());
            Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    public static Fetcher httpFetcher(String url) {
        return () -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(TIMEOUT_MS))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMillis(TIMEOUT_MS))
                        .header("Accept", "application/json")
                        .GET().build();
                HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() != 200) {
                    res.body().close();
                    throw new IOException("http " + res.statusCode());
                }
                try (InputStream in = res.body()) {
                    byte[] data = in.readNBytes(MAX_BYTES + 1);
                    if (data.length > MAX_BYTES) throw new IOException("manifest too large");
                    return new String(data, StandardCharsets.UTF_8);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        };
    }

    public static String bundledText() {
        try (InputStream in = ExtrasManifest.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            return new String(in.readNBytes(MAX_BYTES), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
