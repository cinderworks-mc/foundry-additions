package dev.hartforge.foundryadditions.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtrasLayoutTest {

    @Test
    void listNeverReachesTheButtons() {
        for (int h : new int[] {240, 270, 320, 480}) {
            ExtrasLayout.Area a = ExtrasLayout.list(h);
            assertTrue(a.bottom() <= ExtrasLayout.buttonsTop(h) - 4, "h=" + h);
            assertTrue(a.rows() >= 1);
            assertTrue(a.top() + a.rows() * ExtrasLayout.ROW_H <= a.bottom());
            assertTrue(ExtrasLayout.buttonsTop(h) + 44 <= h);
        }
    }

    @Test
    void smallestWindowStillShowsSeveralRows() {
        // 240 high: 50..186 is 136px, 26px rows -> 5 rows, the rest scrolls
        assertEquals(5, ExtrasLayout.list(240).rows());
    }

    @Test
    void rowsLeaveRoomForTheScrollbar() {
        assertEquals(ExtrasLayout.MAX_ROW_W, ExtrasLayout.rowWidth(960));
        // vanilla puts the 6px scrollbar 10px past the row's right edge
        for (int w : new int[] {320, 427, 480, 640}) {
            assertTrue(w / 2 + ExtrasLayout.rowWidth(w) / 2 + 10 + 6 <= w, "w=" + w);
        }
    }
}
