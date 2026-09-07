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
            new int[]{1440, 1080},
            new int[]{1620, 1080},
            new int[]{1728, 1080},
            new int[]{1920, 1080},
            new int[]{2160, 1080},
            new int[]{2340, 1080},
            new int[]{2400, 1080},
            new int[]{2560, 1080},
            new int[]{2559, 1186},
            new int[]{2640, 1080},
            new int[]{2880, 1080},
            new int[]{1280, 960}
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

    @Test public void asymmetricOffsetsStaySafeAndNeverShiftTheOppositeTeam() {
        BpScreenLayout initial = BpScreenLayout.create(2400, 1080);
        BpScreenLayout moved = initial.shifted(true, false, 22, 10);
        assertTrue(moved != null);
        for (int i = 0; i < initial.slots.size(); i++) {
            var before = initial.slots.get(i); var after = moved.slots.get(i);
            assertEquals(before.crop.left + (before.isLeft() && !before.isBan() ? 22 : 0), after.crop.left);
            assertFalse(after.crop.intersects(moved.candidateExclusion));
        }
        assertTrue(initial.shifted(true, false, 1000, 0) == null);
    }

    @Test public void tabletSearchExtremesDoNotCrashOrSampleCenter() {
        for (double center : new double[]{.075, .150, .233}) for (double side : new double[]{.086, .160, .169}) {
            var layout = BpScreenLayout.adaptive(1440, 1080, center, side, .03, 1.1, .13, .068, .045, .059);
            for (var slot : layout.slots) assertFalse(slot.crop.intersects(layout.candidateExclusion));
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

    @Test
    public void adaptiveGeometryUsesContinuousAnchorsWithoutEnteringCandidateArea() {
        BpScreenLayout layout = BpScreenLayout.adaptive(
            2048,
            941,
            0.168,
            0.116,
            0,
            1,
            0.136,
            0.068,
            0.042,
            0.052
        );

        BpScreenLayout.Slot firstPick = layout.slots.stream()
            .filter(slot -> slot.kind == BpScreenLayout.Kind.LEFT_PICK && slot.index == 0)
            .findFirst()
            .orElseThrow();
        BpScreenLayout.Slot firstBan = layout.slots.stream()
            .filter(slot -> slot.kind == BpScreenLayout.Kind.LEFT_BAN && slot.index == 0)
            .findFirst()
            .orElseThrow();

        assertEquals(158, (firstPick.crop.left + firstPick.crop.right) / 2);
        assertEquals(128, (firstBan.crop.left + firstBan.crop.right) / 2);
        for (BpScreenLayout.Slot slot : layout.slots) {
            assertFalse(slot.crop.intersects(layout.candidateExclusion));
        }
    }

    @Test
    public void expandedUltraWideAndVerticalShiftGeometryStaysInsideTheDraftEdges() {
        BpScreenLayout layout = BpScreenLayout.adaptive(
            2880,
            1250,
            0.233,
            0.160,
            0.030,
            1.10,
            0.195,
            0.072,
            0.054,
            0.066
        );

        assertEquals(20, layout.slots.size());
        for (BpScreenLayout.Slot slot : layout.slots) {
            assertTrue(slot.crop.left >= 0 && slot.crop.top >= 0);
            assertTrue(slot.crop.right <= layout.width && slot.crop.bottom <= layout.height);
            assertFalse(slot.crop.intersects(layout.candidateExclusion));
        }
    }
}
