package dev.hartforge.foundryadditions.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtrasDownloaderTest {

    private HttpServer server;
    private String base;
    private final ExtrasDownloader dl = new ExtrasDownloader(ExtrasDownloader.defaultClient());
    private byte[] jar;

    @BeforeEach
    void start() throws IOException {
        jar = jarBytes();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // same shape as curseforge: api link 307s to the edge, the edge 302s to the file
        server.createContext("/api/v1/mods/1/files/2/download", ex -> {
            ex.getResponseHeaders().add("Location", base + "/edge/files/2/thing-1.21.1-1.0.jar?api-key=x");
            ex.sendResponseHeaders(307, -1);
            ex.close();
        });
        server.createContext("/edge/files/2/thing-1.21.1-1.0.jar", ex -> {
            ex.getResponseHeaders().add("Location", base + "/files/2/thing-1.21.1-1.0.jar");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/files/2/thing-1.21.1-1.0.jar", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/java-archive");
            if (ex.getRequestMethod().equals("HEAD")) {
                ex.getResponseHeaders().add("Content-Length", String.valueOf(jar.length));
                ex.sendResponseHeaders(200, -1);
            } else {
                ex.sendResponseHeaders(200, jar.length);
                try (OutputStream o = ex.getResponseBody()) {
                    o.write(jar);
                }
            }
            ex.close();
        });
        server.createContext("/html.jar", ex -> {
            byte[] page = "<html>log in</html>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, page.length);
            ex.getResponseBody().write(page);
            ex.close();
        });
        server.createContext("/junk.jar", ex -> {
            byte[] junk = "not a zip at all".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, junk.length);
            ex.getResponseBody().write(junk);
            ex.close();
        });
        server.createContext("/gone.jar", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static byte[] jarBytes() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            z.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
            z.write("modId=\"thing\"".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    void followsBothRedirectsAndNamesTheJarAfterTheRealFile(@TempDir Path mods) throws IOException {
        List<long[]> seen = new ArrayList<>();
        ExtrasDownloader.Result r = dl.download(base + "/api/v1/mods/1/files/2/download", mods, "thing-.jar",
                (d, t) -> seen.add(new long[] {d, t}), () -> false);
        assertFalse(r.already());
        assertEquals("thing-1.21.1-1.0.jar", r.file().getFileName().toString());
        assertArrayEquals(jar, Files.readAllBytes(r.file()));
        long[] last = seen.get(seen.size() - 1);
        assertEquals(jar.length, last[0]);
        assertEquals(jar.length, last[1]);
        try (var left = Files.list(mods)) {
            assertEquals(1, left.count(), "no part file left behind");
        }
    }

    @Test
    void sizeFollowsRedirectsToo() {
        assertEquals(jar.length, dl.size(base + "/api/v1/mods/1/files/2/download").orElse(-1));
        assertTrue(dl.size(base + "/gone.jar").isEmpty());
    }

    @Test
    void existingJarIsLeftAlone(@TempDir Path mods) throws IOException {
        Files.writeString(mods.resolve("thing-1.21.1-1.0.jar"), "mine");
        ExtrasDownloader.Result r = dl.download(base + "/api/v1/mods/1/files/2/download", mods, "x.jar",
                (d, t) -> {}, () -> false);
        assertTrue(r.already());
        assertEquals("mine", Files.readString(r.file()));
    }

    @Test
    void webPageIsRefused(@TempDir Path mods) throws IOException {
        IOException e = assertThrows(ExtrasDownloader.Failure.class,
                () -> dl.download(base + "/html.jar", mods, "x.jar", (d, t) -> {}, () -> false));
        assertTrue(e.getMessage().contains("web page"));
        assertEmpty(mods);
    }

    @Test
    void nonZipIsRefusedAndCleanedUp(@TempDir Path mods) throws IOException {
        IOException e = assertThrows(ExtrasDownloader.Failure.class,
                () -> dl.download(base + "/junk.jar", mods, "x.jar", (d, t) -> {}, () -> false));
        assertTrue(e.getMessage().contains("not a mod file"));
        assertEmpty(mods);
    }

    @Test
    void missingFileReadsPlainly(@TempDir Path mods) {
        IOException e = assertThrows(ExtrasDownloader.Failure.class,
                () -> dl.download(base + "/gone.jar", mods, "x.jar", (d, t) -> {}, () -> false));
        assertEquals("the file is gone (error 404). tell an admin", e.getMessage());
    }

    @Test
    void unreachableSiteReadsPlainly(@TempDir Path mods) {
        server.stop(0);
        IOException e = assertThrows(ExtrasDownloader.Failure.class,
                () -> dl.download(base + "/gone.jar", mods, "x.jar", (d, t) -> {}, () -> false));
        assertTrue(e.getMessage().startsWith("could not reach the download site"), e.getMessage());
    }

    @Test
    void cancelStopsWithoutLeavingAnything(@TempDir Path mods) throws IOException {
        assertThrows(InterruptedIOException.class, () -> dl.download(base + "/api/v1/mods/1/files/2/download",
                mods, "x.jar", (d, t) -> {}, () -> true));
        assertEmpty(mods);
    }

    @Test
    void sweepOnlyTouchesOurPartFiles(@TempDir Path mods) throws IOException {
        Files.writeString(mods.resolve("a.jar" + ExtrasDownloader.PART), "half");
        Files.writeString(mods.resolve("b.jar"), "mod");
        Files.writeString(mods.resolve("c.jar.part"), "someone else's");
        ExtrasDownloader.sweep(mods);
        assertFalse(Files.exists(mods.resolve("a.jar" + ExtrasDownloader.PART)));
        assertTrue(Files.exists(mods.resolve("b.jar")));
        assertTrue(Files.exists(mods.resolve("c.jar.part")));
    }

    @Test
    void fileNamesAreKeptSafe() {
        URI cdn = URI.create("https://mediafilez.forgecdn.net/files/9498/175/appliedthaumaturge-1.21.1-1.1.0-BETA.jar");
        assertEquals("appliedthaumaturge-1.21.1-1.1.0-BETA.jar", ExtrasDownloader.fileName(cdn, "", "x.jar"));
        assertEquals("ctm-1.21-1.2.1+3.jar",
                ExtrasDownloader.fileName(URI.create("https://x/files/ctm-1.21-1.2.1%2B3.jar"), "", "x.jar"));
        assertEquals("good.jar", ExtrasDownloader.fileName(cdn, "attachment; filename=\"good.jar\"", "x.jar"));
        assertEquals("_.._evil.jar",
                ExtrasDownloader.fileName(URI.create("https://x/download"), "attachment; filename=\"/../evil.jar\"",
                        "x.jar"));
        assertEquals("thing-1.0.jar", ExtrasDownloader.fileName(URI.create("https://x/download"), "", "thing-1.0.jar"));
        assertEquals("extra-mod.jar", ExtrasDownloader.fileName(URI.create("https://x/"), "", ""));
    }

    private static void assertEmpty(Path dir) throws IOException {
        try (var left = Files.list(dir)) {
            assertEquals(0, left.count());
        }
    }
}
