package com.xiagu.bp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Geometry for the Honor of Kings landscape draft screen.
 *
 * All horizontal distances are derived from screen height and anchored to an edge. That keeps
 * avatar crops stable across 16:9, 18:9, 19.5:9 and 20:9 screens. The center hero browser is an
 * explicit exclusion zone and is never returned as a recognition slot.
 */
final class BpScreenLayout {
    enum Kind { LEFT_PICK, RIGHT_PICK, LEFT_BAN, RIGHT_BAN }

    static final class IntRect {
        final int left;
        final int top;
        final int right;
        final int bottom;

        IntRect(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int width() { return right - left; }
        int height() { return bottom - top; }

        boolean intersects(IntRect other) {
            return left < other.right && right > other.left && top < other.bottom && bottom > other.top;
        }
    }

    static final class Slot {
        final Kind kind;
        final int index;
        final IntRect crop;
        final boolean circular;

        Slot(Kind kind, int index, IntRect crop, boolean circular) {
            this.kind = kind;
            this.index = index;
            this.crop = crop;
            this.circular = circular;
        }

        boolean isBan() { return kind == Kind.LEFT_BAN || kind == Kind.RIGHT_BAN; }
        boolean isLeft() { return kind == Kind.LEFT_PICK || kind == Kind.LEFT_BAN; }
    }

    private static final double[] PICK_Y = {0.171, 0.330, 0.491, 0.652, 0.814};
    private static final double[] BAN_X_FROM_EDGE_IN_HEIGHTS = {0.159, 0.226, 0.293, 0.360, 0.427};

    final int width;
    final int height;
    final IntRect candidateExclusion;
    final List<Slot> slots;

    private BpScreenLayout(int width, int height, IntRect candidateExclusion, List<Slot> slots) {
        this.width = width;
        this.height = height;
        this.candidateExclusion = candidateExclusion;
        this.slots = Collections.unmodifiableList(slots);
    }

    static BpScreenLayout create(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("invalid screen size");

        List<Slot> slots = new ArrayList<>(20);
        int pickSide = Math.max(24, (int) Math.round(height * 0.124));
        int pickCenterFromEdge = (int) Math.round(height * 0.185);
        for (int index = 0; index < PICK_Y.length; index++) {
            int centerY = (int) Math.round(height * PICK_Y[index]);
            slots.add(new Slot(Kind.LEFT_PICK, index, square(width, height, pickCenterFromEdge, centerY, pickSide), false));
            slots.add(new Slot(Kind.RIGHT_PICK, index, square(width, height, width - pickCenterFromEdge, centerY, pickSide), false));
        }

        int banSide = Math.max(18, (int) Math.round(height * 0.066));
        int banCenterY = (int) Math.round(height * 0.045);
        for (int index = 0; index < BAN_X_FROM_EDGE_IN_HEIGHTS.length; index++) {
            int fromEdge = (int) Math.round(height * BAN_X_FROM_EDGE_IN_HEIGHTS[index]);
            slots.add(new Slot(Kind.LEFT_BAN, index, square(width, height, fromEdge, banCenterY, banSide), true));
            slots.add(new Slot(Kind.RIGHT_BAN, index, square(width, height, width - fromEdge, banCenterY, banSide), true));
        }

        IntRect exclusion = new IntRect(
            (int) Math.round(width * 0.225),
            (int) Math.round(height * 0.105),
            (int) Math.round(width * 0.775),
            (int) Math.round(height * 0.720)
        );
        for (Slot slot : slots) {
            if (slot.crop.intersects(exclusion)) {
                throw new IllegalStateException("recognition slot overlaps candidate browser");
            }
        }
        return new BpScreenLayout(width, height, exclusion, slots);
    }

    private static IntRect square(int width, int height, int centerX, int centerY, int side) {
        int half = side / 2;
        int left = clamp(centerX - half, 0, width - 1);
        int top = clamp(centerY - half, 0, height - 1);
        int right = clamp(left + side, left + 1, width);
        int bottom = clamp(top + side, top + 1, height);
        return new IntRect(left, top, right, bottom);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
