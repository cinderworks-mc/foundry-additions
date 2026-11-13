package dev.hartforge.foundryadditions.client;

import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/** every word a player reads on the extras screen, kept free of minecraft so it can be tested */
public final class ExtrasText {

    public enum Tone { GOOD, WARN, BAD, BUSY, PLAIN }

    public record Line(String text, Tone tone) {}

    private ExtrasText() {}

    public static String size(long bytes) {
        if (bytes < 1024 * 1024) return Math.max(1, Math.round(bytes / 1024.0)) + " KB";
        double mb = bytes / (1024.0 * 1024.0);
        return mb < 10 ? String.format(Locale.ROOT, "%.1f MB", mb) : Math.round(mb) + " MB";
    }

    public static String progress(long done, long total) {
        if (done <= 0) return "starting download";
        if (total <= 0) return "downloading, " + size(done) + " so far";
        int pct = (int) Math.min(100, done * 100 / total);
        return "downloading, " + pct + "% of " + size(total);
    }

    public static String names(List<String> names) {
        if (names.size() <= 1) return names.isEmpty() ? "" : names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    public static Line state(ExtrasChecker.Row r, ExtrasSession.Phase phase, long done, long total, String message,
                             List<String> missingNames) {
        switch (phase) {
            case DONE -> {
                if (message.startsWith("now delete")) return new Line("downloaded. " + message, Tone.WARN);
                return new Line("downloaded. restart the game to load it", Tone.GOOD);
            }
            case DOWNLOADING -> {
                return new Line(progress(done, total), Tone.BUSY);
            }
            case WAITING -> {
                return new Line("waiting to download", Tone.BUSY);
            }
            case FAILED -> {
                return new Line("failed: " + message, Tone.BAD);
            }
            default -> {
            }
        }
        String also = missingNames.isEmpty() ? "" : names(missingNames);
        String byHand = r.extra().download().isEmpty() ? ". get it from its page" : "";
        return switch (r.status()) {
            case MISSING -> new Line((also.isEmpty() ? "not installed" : "not installed, also needs " + also) + byHand,
                    Tone.BAD);
            case OUTDATED -> new Line("update needed: you have " + r.have() + ", need " + r.need() + byHand, Tone.WARN);
            case INSTALLED -> also.isEmpty()
                    ? new Line(r.have().isEmpty() ? "installed" : "installed (" + r.have() + ")", Tone.GOOD)
                    : new Line("installed, but it needs " + also + " to work", Tone.BAD);
        };
    }

    // what the row's own button says, blank when there is nothing to press
    public static String action(ExtrasChecker.Row r, ExtrasSession.Phase phase, boolean wantsSomething) {
        return switch (phase) {
            case WAITING, DOWNLOADING -> "stop";
            case FAILED -> "retry";
            case DONE -> "";
            case IDLE -> !wantsSomething ? ""
                    : r.status() == ExtrasChecker.Status.OUTDATED ? "update" : "download";
        };
    }

    public static String actionTip(String label, List<String> names, OptionalLong size) {
        if (label.equals("stop")) return "stop this download";
        if (names.isEmpty()) return "";
        return "downloads " + names(names) + (size.isPresent() ? " (" + size(size.getAsLong()) + ")" : "")
                + " into your mods folder";
    }

    public static String pageLabel(String pageUrl) {
        return pageUrl.toLowerCase(Locale.ROOT).contains("curseforge.com") ? "curseforge" : "website";
    }

    public static String downloadAll(int count, List<OptionalLong> sizes) {
        if (count == 0) return "download all";
        long sum = 0;
        for (OptionalLong s : sizes) {
            if (s.isEmpty()) return "download all (" + count + ")";
            sum += s.getAsLong();
        }
        return "download all (" + count + ", " + size(sum) + ")";
    }

    public static String title(int needing, boolean anyDone) {
        if (needing == 0) return anyDone ? "all set" : "you have everything";
        return "the foundry needs " + needing + " more mod" + (needing == 1 ? "" : "s") + " to join";
    }

    public static Line subtitle(boolean active, boolean anyDone, String notice) {
        if (active) return new Line("downloading. keep this screen open until it finishes", Tone.BUSY);
        if (anyDone) return new Line("restart the game to load these", Tone.GOOD);
        if (!notice.isEmpty()) return new Line(notice, Tone.PLAIN);
        return new Line("these can't ship with the pack. download them here, then restart", Tone.PLAIN);
    }
}
