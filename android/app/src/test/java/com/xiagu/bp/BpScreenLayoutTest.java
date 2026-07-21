package com.xiagu.bp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public final class BpScreenLayoutTest {
    @Test
    public void candidateBrowserIsExcludedAcrossCommonLandscapeRatios() {
        List<int[]> sizes = List.of(
            new int[]{1920, 1080},
            new int[]{2160, 1080},
            new int[]{2340, 1080},
            new int[]{2400, 1080},
            new int[]{2560, 1080},
            new int[]{2559, 1186}
        );

        for (int[] size : sizes) {
            for (BpScreenLayout layout : BpScreenLayout.candidates(size[0], size[1])) {
                assertEquals(20, layout.slots.size());
                for (BpScreenLayout.Slot slot : layout.slots) {
                    assertFalse(size[0] + "x" + size[1], slot.crop.intersects(layout.candidateExclusion));
                    assertTrue(slot.crop.left >= 0 && slot.crop.top >= 0);
                    assertTrue(slot.crop.right <= size[0] && slot.crop.bottom <= size[1]);
                }
            }
        }
    }

    @Test
    public void exposesOnlyTenPicksAndTenBans() {
        BpScreenLayout layout = BpScreenLayout.create(2559, 1186);
        long picks = layout.slots.stream().filter(slot -> !slot.isBan()).count();
        long bans = layout.slots.stream().filter(BpScreenLayout.Slot::isBan).count();
        assertEquals(10, picks);
        assertEquals(10, bans);
    }
}
