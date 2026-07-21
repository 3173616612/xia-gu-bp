package com.xiagu.bp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Geometry for the Honor of Kings landscape draft screen.
 *
 * All horizontal distances are derived from screen height and anchored to an edge. That keeps
 * avatar crops stable across 16:9, 18:9, 19.5:9 and 20:9 screens. The center hero browser is an
 * explicit exclusion zone and is never returned as a recognition slot.
 */
final class BpScreenLayout {
    enum Kind { LEFT_PICK, RIGHT_PICK, LEFT_BAN, RIGHT_BAN }

    enum Profile {
        COMPACT(0.105, 0.076, 0.064, 0.060),
        MEDIUM(0.145, 0.108, 0.061, 0.063),
        WIDE(0.185, 0.159, 0.067, 0.066);

        final double pickCenterFromEdge;
        final double firstBanFromEdge;
        final double banSpacing;
        final double banSide;

        Profile(double pickCenterFromEdge, double firstBanFromEdge, double banSpacing, double banSide) {
            this.pickCenterFromEdge = pickCenterFromEdge;
            this.firstBanFromEdge = firstBanFromEdge;
            this.banSpacing = banSpacing;
            this.banSide = banSide;
        }
    }

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
    final int width;
    final int height;
    final Profile profile;
    final String profileName;
    final IntRect candidateExclusion;
    final List<Slot> slots;

    private BpScreenLayout(
        int width,
        int height,
        Profile profile,
        String profileName,
        IntRect candidateExclusion,
        List<Slot> slots
    ) {
        this.width = width;
        this.height = height;
        this.profile = profile;
        this.profileName = profileName;
        this.candidateExclusion = candidateExclusion;
        this.slots = Collections.unmodifiableList(slots);
    }

    static BpScreenLayout create(int width, int height) {
        return create(width, height, Profile.WIDE);
    }

    static List<BpScreenLayout> candidates(int width, int height) {
        List<BpScreenLayout> output = new ArrayList<>();
        for (Profile profile : Profile.values()) output.add(create(width, height, profile));
        return output;
    }

    static BpScreenLayout create(int width, int height, Profile profile) {
        return create(
            width,
            height,
            profile,
            profile.name(),
            profile.pickCenterFromEdge,
            0.124,
            0,
            1,
            profile.firstBanFromEdge,
            profile.banSpacing,
            0.045,
            profile.banSide
        );
    }

    static BpScreenLayout adaptive(
        int width,
        int height,
        double pickCenterFromEdge,
        double pickSide,
        double pickYOffset,
        double pickSpread,
        double firstBanFromEdge,
        double banSpacing,
        double banCenterY,
        double banSide
    ) {
        String name = String.format(
            Locale.ROOT,
            "ADAPTIVE[p=%.3f/s=%.3f/y=%+.3f×%.3f,b=%.3f/%.3f/y=%.3f/s=%.3f]",
            pickCenterFromEdge,
            pickSide,
            pickYOffset,
            pickSpread,
            firstBanFromEdge,
            banSpacing,
            banCenterY,
            banSide
        );
        return create(
            width,
            height,
            null,
            name,
            pickCenterFromEdge,
            pickSide,
            pickYOffset,
            pickSpread,
            firstBanFromEdge,
            banSpacing,
            banCenterY,
            banSide
        );
    }

    private static BpScreenLayout create(
        int width,
        int height,
        Profile profile,
        String profileName,
        double pickCenterFromEdgeRatio,
        double pickSideRatio,
        double pickYOffset,
        double pickSpread,
        double firstBanFromEdgeRatio,
        double banSpacingRatio,
        double banCenterYRatio,
        double banSideRatio
    ) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("invalid screen size");

        List<Slot> slots = new ArrayList<>(20);
        int pickSide = Math.max(24, (int) Math.round(height * pickSideRatio));
        int pickCenterFromEdge = (int) Math.round(height * pickCenterFromEdgeRatio);
        double verticalCenter = 0.492;
        for (int index = 0; index < PICK_Y.length; index++) {
            double yRatio = verticalCenter + (PICK_Y[index] - verticalCenter) * pickSpread + pickYOffset;
            int centerY = (int) Math.round(height * yRatio);
            slots.add(new Slot(Kind.LEFT_PICK, index, square(width, height, pickCenterFromEdge, centerY, pickSide), false));
            slots.add(new Slot(Kind.RIGHT_PICK, index, square(width, height, width - pickCenterFromEdge, centerY, pickSide), false));
        }

        int banSide = Math.max(18, (int) Math.round(height * banSideRatio));
        int banCenterY = (int) Math.round(height * banCenterYRatio);
        for (int index = 0; index < 5; index++) {
            double distance = firstBanFromEdgeRatio + banSpacingRatio * index;
            int fromEdge = (int) Math.round(height * distance);
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
        return new BpScreenLayout(width, height, profile, profileName, exclusion, slots);
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
