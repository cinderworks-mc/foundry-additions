package dev.hartforge.foundryadditions.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public final class ExtrasDownloader {

    // not .jar, so a half written file in mods is never picked up as a mod
    public static final String PART = ".foundrydl";
    public static final long MAX_BYTES = 512L * 1024 * 1024;

    private static final Pattern CD_NAME = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._+()\\-]");

    public interface Progress {
        void update(long done, long total);
    }

    // already means a jar with that exact name was in mods, nothing was written
    public record Result(Path file, boolean already) {}

    // message is written for players, it goes straight onto the screen
    public static final class Failure extends IOException {
        public Failure(String message) {
            super(message);
        }
    }

    private final HttpClient client;

    public ExtrasDownloader(HttpClient client) {
        this.client = client;
    }

    public static HttpClient defaultClient() {
        // curseforge's api links answer 307 to edge.forgecdn, which answers 302 to the real file
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // a quit mid-download leaves a part file behind, and only we ever write that suffix
    public static void sweep(Path modsDir) {
        try (var files = Files.newDirectoryStream(modsDir, "*" + PART)) {
            for (Path p : files) Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    public OptionalLong size(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> res = client.send(req, HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() != 200) return OptionalLong.empty();
            OptionalLong len = res.headers().firstValueAsLong("content-length");
            return len.isPresent() && len.getAsLong() > 0 ? len : OptionalLong.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OptionalLong.empty();
        } catch (IOException | RuntimeException e) {
            return OptionalLong.empty();
        }
    }

    public Result download(String url, Path modsDir, String fallbackName, Progress progress,
                           BooleanSupplier cancelled) throws IOException {
        HttpResponse<InputStream> res;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();
            res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("stopped");
        } catch (IOException e) {
            throw new Failure(plain(e));
        }
        try (InputStream in = res.body()) {
            int code = res.statusCode();
            if (code != 200) throw new Failure(httpMessage(code));
            String type = res.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
            if (type.startsWith("text/html")) {
                throw new Failure("the site sent a web page instead of the mod. get it from its page");
            }
            long total = res.headers().firstValueAsLong("content-length").orElse(-1);
            if (total > MAX_BYTES) throw new Failure("the file is far bigger than a mod should be, so it was skipped");

            String name = fileName(res.uri(), res.headers().firstValue("content-disposition").orElse(""),
                    fallbackName);
            Path target = modsDir.resolve(name);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return new Result(target, true);

            Path part = modsDir.resolve(name + PART);
            try {
                copy(in, part, total, progress, cancelled);
                checkJar(part);
                place(part, target);
            } finally {
                Files.deleteIfExists(part);
            }
            return new Result(target, false);
        }
    }

    private static void copy(InputStream in, Path part, long total, Progress progress, BooleanSupplier cancelled)
            throws IOException {
        OutputStream out;
        try {
            out = Files.newOutputStream(part);
        } catch (IOException e) {
            throw new Failure(saveMessage(e));
        }
        try (out) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            progress.update(0, total);
            while (true) {
                if (cancelled.getAsBoolean()) throw new InterruptedIOException("stopped");
                int n;
                try {
                    n = in.read(buf);
                } catch (IOException e) {
                    if (cancelled.getAsBoolean()) throw new InterruptedIOException("stopped");
                    throw new Failure(plain(e));
                }
                if (n < 0) break;
                done += n;
                if (done > MAX_BYTES) throw new Failure("the file is far bigger than a mod should be, so it was skipped");
                try {
                    out.write(buf, 0, n);
                } catch (IOException e) {
                    throw new Failure(saveMessage(e));
                }
                progress.update(done, total);
            }
            if (total >= 0 && done != total) throw new Failure("the download was cut off partway. try again");
        }
    }

    // an error page saved as .jar would crash the next launch, so only a real zip goes in
    private static void checkJar(Path part) throws Failure {
        try (ZipFile z = new ZipFile(part.toFile())) {
            if (z.size() == 0) throw new Failure("the downloaded file was empty. try again");
        } catch (ZipException e) {
            throw new Failure("that was not a mod file. get it from its page");
        } catch (IOException e) {
            throw new Failure(saveMessage(e));
        }
    }

    private static void place(Path part, Path target) throws Failure {
        try {
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, target);
            }
        } catch (IOException e) {
            throw new Failure(saveMessage(e));
        }
    }

    // content-disposition first, then the last path segment of wherever the redirects ended up
    public static String fileName(URI finalUri, String contentDisposition, String fallback) {
        Matcher m = CD_NAME.matcher(contentDisposition);
        if (m.find()) {
            String n = clean(URLDecoder.decode(m.group(1), StandardCharsets.UTF_8));
            if (!n.isEmpty()) return n;
        }
        String path = finalUri.getPath();
        if (path != null) {
            String n = clean(path.substring(path.lastIndexOf('/') + 1));
            if (!n.isEmpty()) return n;
        }
        String n = clean(fallback);
        return n.isEmpty() ? "extra-mod.jar" : n;
    }

    private static String clean(String raw) {
        String n = UNSAFE.matcher(raw.trim()).replaceAll("_");
        while (n.startsWith(".")) n = n.substring(1);
        if (!n.toLowerCase(Locale.ROOT).endsWith(".jar") || n.length() <= 4) return "";
        return n;
    }

    public static String httpMessage(int code) {
        if (code == 404 || code == 410) return "the file is gone (error " + code + "). tell an admin";
        if (code == 401 || code == 403) return "the site refused (error " + code + "). get it from its page";
        if (code == 429) return "the download site is busy. wait a minute, then try again";
        if (code >= 500) return "the site is having trouble (error " + code + "). try again later";
        return "the download site answered with error " + code;
    }

    public static String plain(Throwable e) {
        if (e instanceof Failure) return e.getMessage();
        if (e instanceof HttpConnectTimeoutException || e instanceof ConnectException
                || e instanceof UnknownHostException || e.getCause() instanceof UnknownHostException) {
            return "could not reach the download site. check your internet, then try again";
        }
        if (e instanceof HttpTimeoutException) return "the download site took too long to answer. try again";
        if (e instanceof javax.net.ssl.SSLException) return "could not make a safe connection to the download site";
        return "the connection dropped. try again";
    }

    private static String saveMessage(IOException e) {
        if (e instanceof AccessDeniedException) return "the game is not allowed to save into your mods folder";
        String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        if (msg.contains("space")) return "your disk is full";
        return "could not save the file into your mods folder";
    }
}
