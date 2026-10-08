package dev.hartforge.foundryadditions.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtrasLayoutTest {

    @Test
    void clampStaysInsideTheList() {
        assertEquals(0, ExtrasLayout.clamp(-3, 14, 10));
        assertEquals(4, ExtrasLayout.clamp(9, 14, 10));
        assertEquals(2, ExtrasLayout.clamp(2, 14, 10));
        assertEquals(0, ExtrasLayout.clamp(5, 3, 10));
    }

    @Test
    void listNeverReachesTheButtons() {
        for (int h : new int[] {240, 270, 320, 480}) {
            ExtrasLayout.Area a = ExtrasLayout.list(h);
            assertTrue(a.bottom() <= ExtrasLayout.buttonsTop(h) - 6, "h=" + h);
            assertTrue(a.rows() >= 1);
            assertTrue(a.top() + a.rows() * ExtrasLayout.ROW_H <= a.bottom());
            assertTrue(ExtrasLayout.buttonsTop(h) + 44 <= h);
        }
    }

    @Test
    void fourteenExtrasFitOrScrollAtSmallHeights() {
        // 240 high: 34..184 is 150px, 12px rows -> 12 rows, so 14 scrolls by 2
        assertEquals(12, ExtrasLayout.list(240).rows());
        assertEquals(2, ExtrasLayout.clamp(99, 14, ExtrasLayout.list(240).rows()));
        // 320 high: 34..264 is 230px -> 19 rows, all 14 show
        assertEquals(19, ExtrasLayout.list(320).rows());
        assertEquals(0, ExtrasLayout.clamp(99, 14, ExtrasLayout.list(320).rows()));
    }

    @Test
    void scrollbarClickMapsEndsToEnds() {
        ExtrasLayout.Area a = ExtrasLayout.list(240);
        assertEquals(0, ExtrasLayout.offsetForClick(a.top(), a, 14));
        assertEquals(2, ExtrasLayout.offsetForClick(a.bottom(), a, 14));
        assertEquals(0, ExtrasLayout.offsetForClick(-50, a, 14));
        assertEquals(2, ExtrasLayout.offsetForClick(9999, a, 14));
    }
}
