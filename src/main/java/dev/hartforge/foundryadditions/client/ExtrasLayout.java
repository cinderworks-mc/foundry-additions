package dev.hartforge.foundryadditions.client;

public final class ExtrasLayout {

    public static final int ROW_H = 12;
    public static final int LIST_TOP = 34;
    // two rows of 20px buttons, a 4px gap and 6px at the bottom edge
    public static final int BUTTONS_H = 50;

    public record Area(int top, int bottom, int rows) {}

    private ExtrasLayout() {}

    public static int buttonsTop(int screenHeight) {
        return screenHeight - BUTTONS_H;
    }

    public static Area list(int screenHeight) {
        int bottom = Math.max(LIST_TOP + ROW_H, buttonsTop(screenHeight) - 6);
        return new Area(LIST_TOP, bottom, (bottom - LIST_TOP) / ROW_H);
    }

    public static int clamp(int offset, int total, int rows) {
        return Math.max(0, Math.min(offset, total - rows));
    }

    // y of a click on the scrollbar track -> first visible row
    public static int offsetForClick(double y, Area a, int total) {
        double frac = (y - a.top()) / (double) (a.bottom() - a.top());
        return clamp((int) Math.round(frac * (total - a.rows())), total, a.rows());
    }
}
