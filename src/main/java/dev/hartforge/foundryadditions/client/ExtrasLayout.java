package dev.hartforge.foundryadditions.client;

public final class ExtrasLayout {

    // two text lines per row plus the list's own 4px gap
    public static final int ROW_H = 26;
    // title, subtitle and the search box sit above the list
    public static final int LIST_TOP = 50;
    // two rows of 20px buttons, a 4px gap and 6px at the bottom edge
    public static final int BUTTONS_H = 50;
    public static final int MAX_ROW_W = 440;

    public record Area(int top, int bottom, int rows) {}

    private ExtrasLayout() {}

    public static int buttonsTop(int screenHeight) {
        return screenHeight - BUTTONS_H;
    }

    public static Area list(int screenHeight) {
        int bottom = Math.max(LIST_TOP + ROW_H, buttonsTop(screenHeight) - 4);
        return new Area(LIST_TOP, bottom, (bottom - LIST_TOP) / ROW_H);
    }

    // room for the scrollbar on the right at narrow sizes
    public static int rowWidth(int screenWidth) {
        return Math.min(MAX_ROW_W, screenWidth - 32);
    }
}
