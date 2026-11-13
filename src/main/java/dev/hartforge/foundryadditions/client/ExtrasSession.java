package dev.hartforge.foundryadditions.client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * download state for the extras screen. lives outside the screen because other
 * mods can swap our screen out and we build a fresh one when the title comes back.
 */
public final class ExtrasSession {

    public enum Phase { IDLE, WAITING, DOWNLOADING, DONE, FAILED }

    public static final long STALL_NANOS = 30_000_000_000L;

    public static final class Job {
        volatile Phase phase = Phase.IDLE;
        volatile long done;
        volatile long total = -1;
        volatile long lastMove;
        volatile String message = "";
        volatile boolean cancel;
        volatile boolean stalled;
        volatile Future<?> future;

        public Phase phase() {
            return phase;
        }

        public long done() {
            return done;
        }

        public long total() {
            return total;
        }

        public String message() {
            return message;
        }
    }

    private final List<ExtrasChecker.Row> rows;
    private final Map<String, ExtrasChecker.Row> byId = new LinkedHashMap<>();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, Long> sizes = new ConcurrentHashMap<>();
    private final ExtrasDownloader downloader;
    private final Path modsDir;
    private final Path downloadsDir;
    private final ExecutorService pool;
    private final Predicate<String> loaded;
    private final Consumer<String> log;
    private volatile String notice = "";
    private volatile boolean scanning;

    public ExtrasSession(List<ExtrasChecker.Row> found, ExtrasDownloader downloader, Path modsDir, Path downloadsDir,
                         ExecutorService pool, Predicate<String> loaded, Consumer<String> log) {
        this.downloader = downloader;
        this.modsDir = modsDir;
        this.downloadsDir = downloadsDir;
        this.pool = pool;
        this.loaded = loaded;
        this.log = log;
        for (ExtrasChecker.Row r : found) {
            byId.put(r.extra().modId(), r);
            jobs.put(r.extra().modId(), new Job());
        }
        // what needs doing goes on top, sorted once so rows don't jump around mid-download
        List<ExtrasChecker.Row> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparing(r -> !needsAction(r)));
        this.rows = List.copyOf(sorted);
    }

    public List<ExtrasChecker.Row> rows() {
        return rows;
    }

    public Job job(ExtrasChecker.Row r) {
        return jobs.get(r.extra().modId());
    }

    public OptionalLong size(ExtrasChecker.Row r) {
        Long s = sizes.get(r.extra().modId());
        return s == null ? OptionalLong.empty() : OptionalLong.of(s);
    }

    public String notice() {
        return notice;
    }

    public boolean scanning() {
        return scanning;
    }

    public String nameOf(String modId) {
        ExtrasChecker.Row r = byId.get(modId);
        return r == null ? modId : r.extra().name();
    }

    // a listed mod counts once it's downloaded, even though it only loads after a restart
    public boolean present(String modId) {
        ExtrasChecker.Row r = byId.get(modId);
        if (r == null) return loaded.test(modId);
        return r.status() != ExtrasChecker.Status.MISSING || jobs.get(modId).phase == Phase.DONE;
    }

    public List<String> missingRequires(ExtrasChecker.Row r) {
        List<String> out = new ArrayList<>();
        for (String req : r.extra().requires()) {
            if (!req.equals(r.extra().modId()) && !present(req)) out.add(req);
        }
        return out;
    }

    public boolean needsAction(ExtrasChecker.Row r) {
        if (job(r).phase == Phase.DONE) return false;
        return r.status() != ExtrasChecker.Status.INSTALLED || !missingRequires(r).isEmpty();
    }

    public int needingAction() {
        return (int) rows.stream().filter(this::needsAction).count();
    }

    public boolean canDownload(ExtrasChecker.Row r) {
        return !r.extra().download().isEmpty() && r.status() != ExtrasChecker.Status.INSTALLED;
    }

    // the rows one click on this row's button would fetch: itself plus listed mods it needs
    public List<ExtrasChecker.Row> wantedFor(ExtrasChecker.Row r) {
        List<ExtrasChecker.Row> out = new ArrayList<>();
        if (canDownload(r) && idle(r)) out.add(r);
        for (String req : missingRequires(r)) {
            ExtrasChecker.Row dep = byId.get(req);
            if (dep != null && canDownload(dep) && idle(dep) && !out.contains(dep)) out.add(dep);
        }
        return out;
    }

    public List<ExtrasChecker.Row> wantedForAll() {
        return rows.stream().filter(r -> canDownload(r) && idle(r)).toList();
    }

    private boolean idle(ExtrasChecker.Row r) {
        Phase p = job(r).phase;
        return p == Phase.IDLE || p == Phase.FAILED;
    }

    public boolean active() {
        return scanning || jobs.values().stream().anyMatch(j -> j.phase == Phase.WAITING || j.phase == Phase.DOWNLOADING);
    }

    public boolean anyDone() {
        return jobs.values().stream().anyMatch(j -> j.phase == Phase.DONE);
    }

    public void probeSizes() {
        for (ExtrasChecker.Row r : rows) {
            if (!canDownload(r) || sizes.containsKey(r.extra().modId())) continue;
            String url = r.extra().download();
            pool.submit(() -> downloader.size(url).ifPresent(s -> sizes.put(r.extra().modId(), s)));
        }
    }

    public void download(List<ExtrasChecker.Row> wanted) {
        notice = "";
        for (ExtrasChecker.Row r : wanted) {
            Job j = job(r);
            if (!idle(r) || !canDownload(r)) continue;
            j.cancel = false;
            j.stalled = false;
            j.message = "";
            j.done = 0;
            j.total = -1;
            j.phase = Phase.WAITING;
            j.future = pool.submit(() -> run(r, j));
        }
    }

    public void stop(ExtrasChecker.Row r) {
        cancel(job(r), false);
    }

    public void stopAll() {
        jobs.values().forEach(j -> cancel(j, false));
    }

    private static void cancel(Job j, boolean stalled) {
        if (j.phase != Phase.WAITING && j.phase != Phase.DOWNLOADING) return;
        j.stalled = stalled;
        j.cancel = true;
        Future<?> f = j.future;
        if (f != null) f.cancel(true);
        // a cancelled job that never started never runs its own cleanup
        if (j.phase == Phase.WAITING) j.phase = Phase.IDLE;
    }

    // called every client tick while the screen is up
    public void tick(long nanoNow) {
        for (Job j : jobs.values()) {
            if (j.phase == Phase.DOWNLOADING && nanoNow - j.lastMove > STALL_NANOS) cancel(j, true);
        }
    }

    private void run(ExtrasChecker.Row r, Job j) {
        if (j.cancel) return;
        j.lastMove = System.nanoTime();
        j.phase = Phase.DOWNLOADING;
        ExtrasManifest.Extra e = r.extra();
        try {
            ExtrasDownloader.Result res = downloader.download(e.download(), modsDir,
                    e.jarHint() + e.fileVersion() + ".jar",
                    (done, total) -> {
                        j.done = done;
                        j.total = total;
                        j.lastMove = System.nanoTime();
                    },
                    () -> j.cancel);
            j.message = res.already() ? "it was already in your mods folder" : "";
            String newName = res.file().getFileName().toString();
            // two copies of one mod stop the game from starting, so the old one has to go
            if (!r.oldFile().isEmpty() && !r.oldFile().equals(newName)
                    && DownloadsScanner.retireOld(modsDir, r.oldFile()) != DownloadsScanner.RetireResult.RETIRED) {
                j.message = "now delete " + r.oldFile() + " from your mods folder";
            }
            j.phase = Phase.DONE;
            log.accept("extras: " + e.modId() + " -> " + newName);
        } catch (InterruptedIOException stopped) {
            if (j.stalled) {
                j.message = "the download stopped moving. try again";
                j.phase = Phase.FAILED;
            } else {
                j.phase = Phase.IDLE;
            }
        } catch (IOException ex) {
            j.message = ExtrasDownloader.plain(ex);
            j.phase = Phase.FAILED;
            log.accept("extras: " + e.modId() + " failed from " + e.download() + ": " + ex);
        } catch (RuntimeException ex) {
            j.message = "something went wrong. try again";
            j.phase = Phase.FAILED;
            log.accept("extras: " + e.modId() + " failed: " + ex);
        }
    }

    // for players who used the page buttons and saved the jars with their browser
    public void addFromDownloads(Instant now) {
        List<ExtrasChecker.Row> wanted = rows.stream().filter(r -> needsAction(r) && idle(r)
                && r.status() != ExtrasChecker.Status.INSTALLED).toList();
        if (wanted.isEmpty() || scanning) return;
        scanning = true;
        notice = "";
        List<DownloadsScanner.Want> wants = wanted.stream()
                .map(r -> new DownloadsScanner.Want(r.extra().modId(), r.extra().jarHint(), r.extra().minBuild(),
                        r.oldFile()))
                .toList();
        pool.submit(() -> {
            int found = 0;
            try {
                for (DownloadsScanner.Outcome o : DownloadsScanner.installAll(downloadsDir, modsDir, wants, now)) {
                    if (o.jar() == null) continue;
                    found++;
                    Job j = jobs.get(o.modId());
                    switch (o.move()) {
                        case MOVED, COPIED_SOURCE_KEPT, ALREADY_EXISTS -> {
                            j.message = o.retired() == null || o.retired() == DownloadsScanner.RetireResult.RETIRED
                                    ? "" : "now delete " + byId.get(o.modId()).oldFile() + " from your mods folder";
                            j.phase = Phase.DONE;
                        }
                        default -> {
                            j.message = "could not move " + o.jar().getFileName() + " into your mods folder";
                            j.phase = Phase.FAILED;
                        }
                    }
                }
                notice = found == 0 ? "none of these are in your downloads folder yet"
                        : "found " + found + " in your downloads folder";
            } catch (RuntimeException ex) {
                notice = "could not look in your downloads folder";
                log.accept("extras: downloads scan failed: " + ex);
            } finally {
                scanning = false;
            }
        });
    }
}
