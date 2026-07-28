package com.xiagu.bp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local portrait recognizer for the twenty edge-anchored draft slots.
 *
 * The current API portrait is the class label. Multiple crop/scale/brightness descriptors form a
 * small generated training set, while absolute-score and top-two-margin gates prevent empty slots
 * and unfamiliar skins from being guessed. No pixels are uploaded.
 */
final class AvatarRecognitionEngine {
    interface Callback {
        void onReady(BpModels.DetectedLineup lineup);
        void onError(String message);
    }

    interface PrewarmCallback {
        void onReady(int count);
        void onError(String message);
    }

    private static final int GRID = 24;
    private static final double[] SCALES = {1.00, 0.92, 0.84, 0.76};
    private static final double[] X_OFFSETS = {-0.05, 0.0, 0.05};
    private static final double[] Y_OFFSETS = {-0.035, 0.0, 0.035};
    private static final double[] PICK_CENTER_SEEDS = {
        0.075, 0.090, 0.105, 0.120, 0.135, 0.150,
        0.165, 0.180, 0.195, 0.210, 0.225
    };
    private static final double[] PICK_SIDE_SEEDS = {
        0.086, 0.100, 0.114, 0.128, 0.144, 0.160
    };
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LIBRARY_LOCK = new Object();
    private static volatile Library cachedLibrary;

    private AvatarRecognitionEngine() {}

    static void prewarm(Context context, List<BpModels.Hero> heroes, PrewarmCallback callback) {
        Context appContext = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                Library library = loadLibrary(appContext, heroes);
                MAIN.post(() -> callback.onReady(library.references.size()));
            } catch (Exception error) {
                MAIN.post(() -> callback.onError(readable(error)));
            }
        });
    }

    static void recognize(
        Context context,
        Bitmap frame,
        List<BpModels.Hero> rosterHeroes,
        List<BpModels.Hero> originalAvatarHeroes,
        boolean ourSideLeft,
        Callback callback
    ) {
        Context appContext = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                Library library = loadLibrary(appContext, originalAvatarHeroes);
                BpModels.DetectedLineup lineup = recognizeSync(frame, rosterHeroes, library, ourSideLeft);
                MAIN.post(() -> callback.onReady(lineup));
            } catch (Exception error) {
                MAIN.post(() -> callback.onError(readable(error)));
            }
        });
    }

    private static BpModels.DetectedLineup recognizeSync(
        Bitmap frame,
        List<BpModels.Hero> heroes,
        Library library,
        boolean ourSideLeft
    ) {
        BpScreenLayout.IntRect viewport = detectContentViewport(frame);
        boolean cropped = viewport.left > 0
            || viewport.top > 0
            || viewport.right < frame.getWidth()
            || viewport.bottom < frame.getHeight();
        if (!cropped) return recognizeViewportSync(frame, heroes, library, ourSideLeft);

        Bitmap content = Bitmap.createBitmap(
            frame,
            viewport.left,
            viewport.top,
            viewport.width(),
            viewport.height()
        );
        try {
            BpModels.DetectedLineup output = recognizeViewportSync(content, heroes, library, ourSideLeft);
            output.layoutProfile += String.format(
                Locale.ROOT,
                ",viewport=%d,%d,%d,%d",
                viewport.left,
                viewport.top,
                viewport.right,
                viewport.bottom
            );
            return output;
        } finally {
            content.recycle();
        }
    }

    private static BpModels.DetectedLineup recognizeViewportSync(
        Bitmap frame,
        List<BpModels.Hero> heroes,
        Library library,
        boolean ourSideLeft
    ) {
        boolean obscureLeftBans = hasUpperLeftVideoOverlay(frame);
        BpScreenLayout layout = selectLayout(frame, library, obscureLeftBans);
        Map<Integer, BpModels.Hero> heroesById = new HashMap<>();
        for (BpModels.Hero hero : heroes) heroesById.put(hero.id, hero);

        List<SlotMatch> acceptedPicks = new ArrayList<>();
        List<SlotMatch> acceptedBans = new ArrayList<>();
        List<String> slotTrace = new ArrayList<>();
        int rejectedPicks = 0;
        int rejectedBans = 0;
        for (BpScreenLayout.Slot slot : layout.slots) {
            SlotMatch match = matchSlot(frame, slot, library);
            boolean accepted = match.accepted
                && !(obscureLeftBans && slot.kind == BpScreenLayout.Kind.LEFT_BAN);
            BpModels.Hero tracedHero = heroesById.get(match.heroId);
            slotTrace.add(
                slot.kind + "[" + slot.index + "]="
                    + (tracedHero == null ? match.heroId : tracedHero.name)
                    + (accepted ? " ✓" : " ×")
                    + String.format(Locale.ROOT, " %.3f/%.3f", match.score, match.margin)
            );
            if (!accepted) {
                if (slot.isBan()) rejectedBans++;
                else rejectedPicks++;
                continue;
            }
            if (slot.isBan()) acceptedBans.add(match);
            else acceptedPicks.add(match);
        }

        // A hero cannot occupy two pick slots. Preserve only the stronger observation.
        Map<Integer, SlotMatch> uniquePicks = new LinkedHashMap<>();
        for (SlotMatch match : acceptedPicks) {
            SlotMatch previous = uniquePicks.get(match.heroId);
            if (previous == null || match.score > previous.score) uniquePicks.put(match.heroId, match);
        }

        BpModels.DetectedLineup output = new BpModels.DetectedLineup();
        output.layoutProfile = layout.profileName;
        output.slotTrace.addAll(slotTrace);
        double confidenceTotal = 0;
        int confidenceCount = 0;
        boolean hasMultiRole = false;
        Set<Integer> pickedIds = new HashSet<>();
        List<SlotMatch> orderedPicks = new ArrayList<>(uniquePicks.values());
        orderedPicks.sort(Comparator.comparingInt((SlotMatch value) -> value.slot.index)
            .thenComparing(value -> value.slot.kind));
        for (SlotMatch match : orderedPicks) {
            BpModels.Hero hero = heroesById.get(match.heroId);
            if (hero == null) continue;
            pickedIds.add(hero.id);
            hasMultiRole |= hero.positions.size() > 1;
            confidenceTotal += match.score;
            confidenceCount++;
            boolean left = match.slot.kind == BpScreenLayout.Kind.LEFT_PICK;
            if (left == ourSideLeft) output.allies.add(hero);
            else output.enemies.add(hero);
        }

        Set<Integer> banIds = new LinkedHashSet<>();
        for (SlotMatch match : acceptedBans) {
            if (!pickedIds.contains(match.heroId)) banIds.add(match.heroId);
            confidenceTotal += match.score;
            confidenceCount++;
        }
        for (Integer heroId : banIds) {
            BpModels.Hero hero = heroesById.get(heroId);
            if (hero != null) output.bans.add(hero);
        }

        output.confidence = confidenceCount == 0 ? 0 : confidenceTotal / confidenceCount;
        if (obscureLeftBans) {
            output.issues.add("检测到左上视频水印，已忽略被遮挡的左侧 BAN 槽位。");
        }
        if (output.allies.isEmpty() && output.enemies.isEmpty()) {
            output.issues.add("没有在两侧已选槽识别到高置信度头像，请确认处于横屏 BP 界面。");
        }
        if (rejectedPicks > 0 || rejectedBans > 0) {
            output.issues.add("空槽或低置信度槽位已跳过（已选 " + rejectedPicks + "、BAN " + rejectedBans + "），未强行猜测。");
        }
        if (uniquePicks.size() < acceptedPicks.size()) {
            output.issues.add("重复头像只保留了置信度更高的槽位，建议重新识别或核对画面。");
        }
        if (hasMultiRole) output.issues.add("检测到多位置英雄，推荐会按其可用分路软加权。");
        if (output.allies.size() != 5 || output.enemies.size() != 5) {
            output.issues.add("当前为我方 " + output.allies.size() + " 人、敌方 " + output.enemies.size() + " 人；按画面现有阵容计算，不强制补齐五路。");
        }
        return output;
    }

    /**
     * MediaProjection may include a navigation strip or letterbox on devices whose panel is wider
     * than the game's render viewport. Strip only full-width/full-height, very dark and nearly
     * uniform borders; ordinary dark game backgrounds are deliberately left untouched.
     */
    private static BpScreenLayout.IntRect detectContentViewport(Bitmap frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        int horizontalLimit = Math.max(1, Math.round(width * 0.14f));
        int verticalLimit = Math.max(1, Math.round(height * 0.14f));

        int left = 0;
        while (left < horizontalLimit && isSolidDarkColumn(frame, left)) left++;
        int right = width;
        while (right > width - horizontalLimit && isSolidDarkColumn(frame, right - 1)) right--;
        int top = 0;
        while (top < verticalLimit && isSolidDarkRow(frame, top)) top++;
        int bottom = height;
        while (bottom > height - verticalLimit && isSolidDarkRow(frame, bottom - 1)) bottom--;

        int minimumHorizontalTrim = Math.max(6, Math.round(width * 0.004f));
        int minimumVerticalTrim = Math.max(6, Math.round(height * 0.006f));
        if (left < minimumHorizontalTrim) left = 0;
        if (width - right < minimumHorizontalTrim) right = width;
        if (top < minimumVerticalTrim) top = 0;
        if (height - bottom < minimumVerticalTrim) bottom = height;
        int leftTrim = left;
        int rightTrim = width - right;
        int topTrim = top;
        int bottomTrim = height - bottom;
        if (!balancedBorders(leftTrim, rightTrim)) {
            left = 0;
            right = width;
        }
        if (!balancedBorders(topTrim, bottomTrim)) {
            top = 0;
            bottom = height;
        }
        if (right - left < width * 0.78 || bottom - top < height * 0.78) {
            return new BpScreenLayout.IntRect(0, 0, width, height);
        }
        return new BpScreenLayout.IntRect(left, top, right, bottom);
    }

    private static boolean balancedBorders(int first, int second) {
        if (first == 0 && second == 0) return true;
        if (first == 0 || second == 0) return false;
        int maximum = Math.max(first, second);
        return Math.abs(first - second) <= Math.max(8, Math.round(maximum * 0.35f));
    }

    private static boolean isSolidDarkRow(Bitmap frame, int y) {
        return isSolidDarkLine(frame, y, true);
    }

    private static boolean isSolidDarkColumn(Bitmap frame, int x) {
        return isSolidDarkLine(frame, x, false);
    }

    private static boolean isSolidDarkLine(Bitmap frame, int fixedCoordinate, boolean horizontal) {
        int length = horizontal ? frame.getWidth() : frame.getHeight();
        int step = Math.max(1, length / 220);
        double total = 0;
        double squared = 0;
        int samples = 0;
        for (int offset = 0; offset < length; offset += step) {
            int x = horizontal ? offset : fixedCoordinate;
            int y = horizontal ? fixedCoordinate : offset;
            int color = frame.getPixel(x, y);
            double luminance = Color.red(color) * 0.299
                + Color.green(color) * 0.587
                + Color.blue(color) * 0.114;
            total += luminance;
            squared += luminance * luminance;
            samples++;
        }
        if (samples == 0) return false;
        double mean = total / samples;
        double variance = Math.max(0, squared / samples - mean * mean);
        return mean <= 42 && Math.sqrt(variance) <= 9;
    }

    private static boolean hasUpperLeftVideoOverlay(Bitmap frame) {
        int limitX = Math.max(1, Math.round(frame.getWidth() * 0.38f));
        int limitY = Math.max(1, Math.round(frame.getHeight() * 0.11f));
        int step = Math.max(2, frame.getHeight() / 420);
        int whitePixels = 0;
        int sampled = 0;
        for (int y = 0; y < limitY; y += step) {
            for (int x = 0; x < limitX; x += step) {
                int color = frame.getPixel(x, y);
                int red = Color.red(color);
                int green = Color.green(color);
                int blue = Color.blue(color);
                int maximum = Math.max(red, Math.max(green, blue));
                int minimum = Math.min(red, Math.min(green, blue));
                if (maximum > 210 && maximum - minimum < 35) whitePixels++;
                sampled++;
            }
        }
        return sampled > 0 && (double) whitePixels / sampled > 0.020;
    }

    private static BpScreenLayout selectLayout(Bitmap frame, Library library, boolean obscureLeftBans) {
        PickGeometry picks = searchPickGeometry(frame, library);
        BanGeometry bans = searchBanGeometry(frame, library, picks, obscureLeftBans);
        return adaptiveLayout(frame, picks, bans);
    }

    private static PickGeometry searchPickGeometry(Bitmap frame, Library library) {
        List<PickCandidate> seeds = new ArrayList<>();
        for (double center : PICK_CENTER_SEEDS) {
            for (double side : PICK_SIDE_SEEDS) {
                PickGeometry candidate = new PickGeometry(center, side, 0, 1);
                double quality = coarseLayoutQuality(frame, adaptiveLayout(frame, candidate, BanGeometry.DEFAULT), library, false, false);
                seeds.add(new PickCandidate(candidate, quality));
            }
        }
        PickGeometry best = denseBestPick(frame, library, seeds, 8);

        List<PickCandidate> refinements = new ArrayList<>();
        PickGeometry coarse = best;
        for (double centerDelta = -0.008; centerDelta <= 0.0081; centerDelta += 0.002) {
            for (double sideDelta = -0.009; sideDelta <= 0.0091; sideDelta += 0.003) {
                PickGeometry candidate = new PickGeometry(
                    coarse.centerFromEdge + centerDelta,
                    coarse.side + sideDelta,
                    0,
                    1
                );
                double quality = coarseLayoutQuality(frame, adaptiveLayout(frame, candidate, BanGeometry.DEFAULT), library, false, false);
                refinements.add(new PickCandidate(candidate, quality));
            }
        }
        best = denseBestPick(frame, library, refinements, 8);

        List<PickCandidate> verticals = new ArrayList<>();
        PickGeometry horizontal = best;
        for (double yOffset : new double[]{-0.030, -0.020, -0.010, 0, 0.010, 0.020, 0.030}) {
            for (double spread : new double[]{0.90, 0.95, 1, 1.05, 1.10}) {
                PickGeometry candidate = new PickGeometry(horizontal.centerFromEdge, horizontal.side, yOffset, spread);
                double quality = coarseLayoutQuality(frame, adaptiveLayout(frame, candidate, BanGeometry.DEFAULT), library, false, false);
                verticals.add(new PickCandidate(candidate, quality));
            }
        }
        return denseBestPick(frame, library, verticals, 10);
    }

    private static PickGeometry denseBestPick(
        Bitmap frame,
        Library library,
        List<PickCandidate> candidates,
        int finalistCount
    ) {
        candidates.sort(Comparator.comparingDouble((PickCandidate value) -> value.quality).reversed());
        PickGeometry best = candidates.get(0).geometry;
        double bestQuality = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < Math.min(finalistCount, candidates.size()); index++) {
            PickGeometry candidate = candidates.get(index).geometry;
            double quality = coarseLayoutQuality(
                frame,
                adaptiveLayout(frame, candidate, BanGeometry.DEFAULT),
                library,
                false,
                false,
                true
            );
            if (quality > bestQuality) {
                bestQuality = quality;
                best = candidate;
            }
        }
        return best;
    }

    private static BanGeometry searchBanGeometry(
        Bitmap frame,
        Library library,
        PickGeometry picks,
        boolean ignoreLeftBans
    ) {
        // Every observed UI keeps the nearest BAN center about 0.03 screen-heights closer to the
        // edge than the large pick column. Keeping that relation prevents a one-slot shift when
        // the first empty edge crop and the next four real portraits would otherwise score well.
        double predictedFirst = Math.max(0.060, Math.min(0.185, picks.centerFromEdge - 0.032));
        BanGeometry best = new BanGeometry(predictedFirst, 0.064, 0.045, 0.059);
        double bestQuality = coarseLayoutQuality(
            frame,
            adaptiveLayout(frame, picks, best),
            library,
            true,
            ignoreLeftBans
        );
        for (double firstDelta = -0.018; firstDelta <= 0.0181; firstDelta += 0.006) {
            double first = predictedFirst + firstDelta;
            for (double side : new double[]{0.045, 0.052, 0.059, 0.066}) {
                BanGeometry candidate = new BanGeometry(first, 0.064, 0.045, side);
                double quality = coarseLayoutQuality(frame, adaptiveLayout(frame, picks, candidate), library, true, ignoreLeftBans);
                if (quality > bestQuality) {
                    bestQuality = quality;
                    best = candidate;
                }
            }
        }

        BanGeometry coarse = best;
        for (double firstDelta = -0.006; firstDelta <= 0.0061; firstDelta += 0.002) {
            for (double sideDelta = -0.006; sideDelta <= 0.0061; sideDelta += 0.003) {
                BanGeometry candidate = new BanGeometry(
                    coarse.firstFromEdge + firstDelta,
                    coarse.spacing,
                    coarse.centerY,
                    coarse.side + sideDelta
                );
                double quality = coarseLayoutQuality(frame, adaptiveLayout(frame, picks, candidate), library, true, ignoreLeftBans);
                if (quality > bestQuality) {
                    bestQuality = quality;
                    best = candidate;
                }
            }
        }

        BanGeometry horizontal = best;
        for (double centerY : new double[]{0.036, 0.042, 0.048, 0.054}) {
            for (double spacing : new double[]{0.060, 0.064, 0.068, 0.072}) {
                BanGeometry candidate = new BanGeometry(
                    horizontal.firstFromEdge,
                    spacing,
                    centerY,
                    horizontal.side
                );
                double quality = coarseLayoutQuality(frame, adaptiveLayout(frame, picks, candidate), library, true, ignoreLeftBans);
                if (quality > bestQuality) {
                    bestQuality = quality;
                    best = candidate;
                }
            }
        }

        // The anchor pass finds the correct neighborhood. A narrow full-descriptor pass then
        // resolves 8-15 px shifts that matter disproportionately for the tiny BAN portraits.
        BanGeometry neighborhood = best;
        double bestFullQuality = Double.NEGATIVE_INFINITY;
        for (double firstDelta : new double[]{-0.008, -0.004, 0, 0.004, 0.008}) {
            BanGeometry candidate = new BanGeometry(
                neighborhood.firstFromEdge + firstDelta,
                neighborhood.spacing,
                neighborhood.centerY,
                neighborhood.side
            );
            double quality = fullBanLayoutQuality(
                frame,
                adaptiveLayout(frame, picks, candidate),
                library,
                ignoreLeftBans
            );
            if (quality > bestFullQuality) {
                bestFullQuality = quality;
                best = candidate;
            }
        }
        return best;
    }

    private static BpScreenLayout adaptiveLayout(Bitmap frame, PickGeometry picks, BanGeometry bans) {
        return BpScreenLayout.adaptive(
            frame.getWidth(),
            frame.getHeight(),
            picks.centerFromEdge,
            picks.side,
            picks.yOffset,
            picks.spread,
            bans.firstFromEdge,
            bans.spacing,
            bans.centerY,
            bans.side
        );
    }

    /**
     * Scores only alternating slots with one descriptor per crop/reference. This makes continuous
     * geometry search cheap enough to run on-device before the full multi-scale recognition pass.
     */
    private static double coarseLayoutQuality(
        Bitmap frame,
        BpScreenLayout layout,
        Library library,
        boolean bans,
        boolean ignoreLeftBans
    ) {
        return coarseLayoutQuality(frame, layout, library, bans, ignoreLeftBans, false);
    }

    private static double coarseLayoutQuality(
        Bitmap frame,
        BpScreenLayout layout,
        Library library,
        boolean bans,
        boolean ignoreLeftBans,
        boolean includeAllPickRows
    ) {
        List<CoarseEvidence> evidence = new ArrayList<>();
        for (BpScreenLayout.Slot slot : layout.slots) {
            if (slot.isBan() != bans) continue;
            if (!bans && !includeAllPickRows && slot.index % 2 != 0) continue;
            if (ignoreLeftBans && slot.kind == BpScreenLayout.Kind.LEFT_BAN) continue;
            Descriptor query = describeCrop(frame, slot.crop, 0.88, 0, 0, slot.circular);
            if (query.contrast < 0.085) {
                evidence.add(new CoarseEvidence(0, slot.index, slot.isLeft()));
                continue;
            }

            double bestScore = -1;
            double secondScore = -1;
            for (Reference reference : library.references) {
                Descriptor anchor = bans ? reference.circularAnchor : reference.squareAnchor;
                double score = similarity(query, anchor);
                if (score > bestScore) {
                    secondScore = bestScore;
                    bestScore = score;
                } else if (score > secondScore) {
                    secondScore = score;
                }
            }
            double margin = Math.max(0, bestScore - secondScore);
            // Keep the same portrait evidence scale used by the proven real-screen fixtures.
            // Dense finalist ranking adds row/side coverage without changing what a strong
            // portrait looks like, so expanding the geometry range cannot demote a known layout.
            double score = Math.max(0, bestScore - 0.20)
                + Math.min(0.12, margin) * 0.6;
            evidence.add(new CoarseEvidence(score, slot.index, slot.isLeft()));
        }
        evidence.sort(Comparator.comparingDouble((CoarseEvidence value) -> value.score).reversed());
        int evidenceSlots = Math.min(includeAllPickRows ? 6 : 4, evidence.size());
        double quality = 0;
        Set<Integer> rows = new HashSet<>();
        boolean leftEvidence = false;
        boolean rightEvidence = false;
        for (int i = 0; i < evidenceSlots; i++) {
            CoarseEvidence item = evidence.get(i);
            quality += item.score * (1.0 - i * 0.07);
            if (item.score >= 0.12) {
                rows.add(item.index);
                leftEvidence |= item.left;
                rightEvidence |= !item.left;
            }
        }
        if (includeAllPickRows) {
            quality += rows.size() * 0.025;
            if (leftEvidence && rightEvidence) quality += 0.05;
        }
        return quality;
    }

    private static double fullBanLayoutQuality(
        Bitmap frame,
        BpScreenLayout layout,
        Library library,
        boolean ignoreLeftBans
    ) {
        double quality = 0;
        for (BpScreenLayout.Slot slot : layout.slots) {
            if (!slot.isBan() || slot.index % 2 != 0) continue;
            if (ignoreLeftBans && slot.kind == BpScreenLayout.Kind.LEFT_BAN) continue;
            SlotMatch match = matchSlot(frame, slot, library);
            if (match.accepted) quality += 2 + match.score + Math.min(0.25, match.margin);
            else quality += Math.max(0, match.score - 0.55) * 0.1;
        }
        return quality;
    }

    private static SlotMatch matchSlot(Bitmap frame, BpScreenLayout.Slot slot, Library library) {
        Bitmap crop = Bitmap.createBitmap(
            frame,
            slot.crop.left,
            slot.crop.top,
            slot.crop.width(),
            slot.crop.height()
        );
        List<Descriptor> fullQueries;
        List<Descriptor> compactQueries;
        try {
            fullQueries = descriptors(crop, slot.circular, true);
            compactQueries = descriptors(crop, slot.circular, false);
        } finally {
            crop.recycle();
        }

        // Cheap pass over every hero, then expensive all-offset matching only for the shortlist.
        List<ScoredReference> shortlist = new ArrayList<>(library.references.size());
        for (Reference reference : library.references) {
            List<Descriptor> compact = slot.circular ? reference.circularCompact : reference.squareCompact;
            shortlist.add(new ScoredReference(reference, bestSimilarity(compactQueries, compact)));
        }
        shortlist.sort(Comparator.comparingDouble((ScoredReference value) -> value.score).reversed());

        List<ScoredHero> scores = new ArrayList<>(Math.min(16, shortlist.size()));
        for (int i = 0; i < Math.min(16, shortlist.size()); i++) {
            Reference reference = shortlist.get(i).reference;
            List<Descriptor> full = slot.circular ? reference.circular : reference.square;
            scores.add(new ScoredHero(reference.heroId, bestSimilarity(fullQueries, full)));
        }
        scores.sort(Comparator.comparingDouble((ScoredHero value) -> value.score).reversed());
        if (scores.isEmpty()) return new SlotMatch(slot, 0, 0, 0, false);

        ScoredHero first = scores.get(0);
        double second = scores.size() > 1 ? scores.get(1).score : -1;
        double margin = first.score - second;
        Descriptor stats = fullQueries.get(4);
        double minimumScore = 0.50;
        double minimumMargin = slot.isBan() ? 0.018 : 0.035;
        boolean accepted = stats.contrast >= 0.105
            && first.score >= minimumScore
            && margin >= minimumMargin;
        return new SlotMatch(slot, first.heroId, first.score, margin, accepted);
    }

    private static double bestSimilarity(List<Descriptor> queries, List<Descriptor> candidates) {
        double best = -1;
        for (Descriptor query : queries) {
            for (Descriptor candidate : candidates) best = Math.max(best, similarity(query, candidate));
        }
        return best;
    }

    private static Library loadLibrary(Context context, List<BpModels.Hero> heroes) throws Exception {
        String fingerprint = fingerprint(heroes);
        Library current = cachedLibrary;
        if (current != null && current.fingerprint.equals(fingerprint)) return current;

        synchronized (LIBRARY_LOCK) {
            current = cachedLibrary;
            if (current != null && current.fingerprint.equals(fingerprint)) return current;
            List<Reference> references = new ArrayList<>();
            for (BpModels.Hero hero : heroes) {
                if (hero.id <= 0 || hero.avatarUrl == null || hero.avatarUrl.isBlank()) continue;
                Bitmap avatar = loadAvatar(context, hero);
                if (avatar == null) continue;
                try {
                    references.add(new Reference(
                        hero.id,
                        descriptors(avatar, false, true),
                        descriptors(avatar, true, true),
                        descriptors(avatar, false, false),
                        descriptors(avatar, true, false),
                        describe(avatar, 0.88, 0, 0, false),
                        describe(avatar, 0.88, 0, 0, true)
                    ));
                } finally {
                    avatar.recycle();
                }
            }
            if (references.size() < Math.min(100, heroes.size())) {
                throw new IllegalStateException("头像库仅加载 " + references.size() + "/" + heroes.size() + "，请联网后重试");
            }
            current = new Library(fingerprint, references);
            cachedLibrary = current;
            return current;
        }
    }

    private static Bitmap loadAvatar(Context context, BpModels.Hero hero) {
        String key = hero.id + "-" + shortHash(hero.avatarUrl) + ".img";
        File directory = new File(context.getFilesDir(), "avatar-library");
        File cached = new File(directory, key);
        Bitmap decoded = decodeFile(cached);
        if (decoded != null) return decoded;

        try (InputStream input = context.getAssets().open("avatar_seed/" + key)) {
            decoded = BitmapFactory.decodeStream(input);
            if (decoded != null) return decoded;
        } catch (Exception ignored) {
            // A changed URL is expected after a game update; download only that new portrait.
        }

        try {
            if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("cannot create avatar cache");
            File temporary = new File(directory, key + ".part");
            HttpURLConnection connection = (HttpURLConnection) new URL(hero.avatarUrl).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("Accept", "image/*");
            connection.setInstanceFollowRedirects(true);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("avatar HTTP " + status);
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            } finally {
                connection.disconnect();
            }
            if (!temporary.renameTo(cached)) {
                try (FileInputStream input = new FileInputStream(temporary); FileOutputStream output = new FileOutputStream(cached)) {
                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
                }
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
            decoded = decodeFile(cached);
            if (decoded != null) return decoded;
        } catch (Exception ignored) {
            // Fall through to any older seed/cache for the same hero so one CDN failure is harmless.
        }

        File[] fallbacks = directory.listFiles((dir, name) -> name.startsWith(hero.id + "-") && name.endsWith(".img"));
        if (fallbacks != null) {
            for (File fallback : fallbacks) {
                decoded = decodeFile(fallback);
                if (decoded != null) return decoded;
            }
        }
        try {
            String[] seeds = context.getAssets().list("avatar_seed");
            if (seeds != null) {
                for (String seed : seeds) {
                    if (!seed.startsWith(hero.id + "-")) continue;
                    try (InputStream input = context.getAssets().open("avatar_seed/" + seed)) {
                        decoded = BitmapFactory.decodeStream(input);
                        if (decoded != null) return decoded;
                    }
                }
            }
        } catch (Exception ignored) {
            // No usable fallback.
        }
        return null;
    }

    private static Bitmap decodeFile(File file) {
        if (!file.isFile() || file.length() == 0) return null;
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    private static List<Descriptor> descriptors(Bitmap bitmap, boolean circular) {
        return descriptors(bitmap, circular, true);
    }

    private static List<Descriptor> descriptors(Bitmap bitmap, boolean circular, boolean expandedOffsets) {
        double[] xOffsets = X_OFFSETS;
        double[] yOffsets = expandedOffsets ? Y_OFFSETS : new double[]{0.0};
        List<Descriptor> output = new ArrayList<>(SCALES.length * xOffsets.length * yOffsets.length);
        for (double scale : SCALES) {
            for (double xOffset : xOffsets) {
                for (double yOffset : yOffsets) output.add(describe(bitmap, scale, xOffset, yOffset, circular));
            }
        }
        return output;
    }

    private static Descriptor describe(Bitmap bitmap, double scale, double xOffset, double yOffset, boolean circular) {
        return describeCrop(
            bitmap,
            new BpScreenLayout.IntRect(0, 0, bitmap.getWidth(), bitmap.getHeight()),
            scale,
            xOffset,
            yOffset,
            circular
        );
    }

    private static Descriptor describeCrop(
        Bitmap bitmap,
        BpScreenLayout.IntRect crop,
        double scale,
        double xOffset,
        double yOffset,
        boolean circular
    ) {
        float[] gray = new float[GRID * GRID];
        float[] edge = new float[GRID * GRID];
        boolean[] valid = new boolean[GRID * GRID];
        float[] histogram = new float[24];
        int width = crop.width();
        int height = crop.height();
        double side = Math.min(width, height) * scale;
        double centerX = crop.left + (width - 1) * (0.5 + xOffset);
        double centerY = crop.top + (height - 1) * (0.5 + yOffset);
        double startX = centerX - side * 0.5;
        double startY = centerY - side * 0.5;
        double grayTotal = 0;
        double saturationTotal = 0;
        int count = 0;
        float[] hsv = new float[3];

        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                int index = y * GRID + x;
                double nx = (x + 0.5) / GRID - 0.5;
                double ny = (y + 0.5) / GRID - 0.5;
                if (circular && nx * nx + ny * ny > 0.235) continue;
                int sourceX = clamp(
                    (int) Math.round(startX + (x + 0.5) * side / GRID),
                    crop.left,
                    crop.right - 1
                );
                int sourceY = clamp(
                    (int) Math.round(startY + (y + 0.5) * side / GRID),
                    crop.top,
                    crop.bottom - 1
                );
                int color = bitmap.getPixel(sourceX, sourceY);
                int red = Color.red(color);
                int green = Color.green(color);
                int blue = Color.blue(color);
                float luminance = (float) ((red * 0.299 + green * 0.587 + blue * 0.114) / 255.0);
                gray[index] = luminance;
                valid[index] = true;
                grayTotal += luminance;
                count++;

                Color.RGBToHSV(red, green, blue, hsv);
                saturationTotal += hsv[1];
                int hueBin = Math.min(15, (int) (hsv[0] / 360f * 16));
                int saturationBin = Math.min(3, (int) (hsv[1] * 4));
                int valueBin = Math.min(3, (int) (hsv[2] * 4));
                histogram[hueBin] += hsv[1] < 0.12f ? 0.2f : 1f;
                histogram[16 + saturationBin] += 1f;
                histogram[20 + valueBin] += 1f;
            }
        }

        double mean = count == 0 ? 0 : grayTotal / count;
        double variance = 0;
        for (int i = 0; i < gray.length; i++) {
            if (!valid[i]) continue;
            double centered = gray[i] - mean;
            variance += centered * centered;
            gray[i] = (float) centered;
        }
        normalize(gray);

        for (int y = 0; y < GRID - 1; y++) {
            for (int x = 0; x < GRID - 1; x++) {
                int index = y * GRID + x;
                if (!valid[index] || !valid[index + 1] || !valid[index + GRID]) continue;
                edge[index] = Math.abs(gray[index + 1] - gray[index]) + Math.abs(gray[index + GRID] - gray[index]);
            }
        }
        normalize(edge);
        float histogramTotal = 0;
        for (float value : histogram) histogramTotal += value;
        if (histogramTotal > 0) {
            for (int i = 0; i < histogram.length; i++) histogram[i] /= histogramTotal;
        }
        return new Descriptor(
            gray,
            edge,
            histogram,
            mean,
            count == 0 ? 0 : Math.sqrt(variance / count),
            count == 0 ? 0 : saturationTotal / count
        );
    }

    private static double similarity(Descriptor left, Descriptor right) {
        double gray = dot(left.gray, right.gray);
        double edge = dot(left.edge, right.edge);
        double histogram = 0;
        for (int i = 0; i < left.histogram.length; i++) {
            histogram += Math.min(left.histogram[i], right.histogram[i]);
        }
        return gray * 0.72 + edge * 0.16 + histogram * 0.12;
    }

    private static double dot(float[] left, float[] right) {
        double output = 0;
        for (int i = 0; i < left.length; i++) output += left[i] * right[i];
        return output;
    }

    private static void normalize(float[] values) {
        double norm = 0;
        for (float value : values) norm += value * value;
        if (norm <= 1e-12) return;
        double divisor = Math.sqrt(norm);
        for (int i = 0; i < values.length; i++) values[i] /= (float) divisor;
    }

    static String fingerprint(List<BpModels.Hero> heroes) {
        List<String> rows = new ArrayList<>();
        for (BpModels.Hero hero : heroes) rows.add(hero.id + "|" + hero.avatarUrl);
        rows.sort(String::compareTo);
        return shortHash(String.join("\n", rows));
    }

    static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            for (int i = 0; i < 6; i++) output.append(String.format(Locale.ROOT, "%02x", digest[i]));
            return output.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String readable(Exception error) {
        String detail = error.getMessage();
        return detail == null || detail.isBlank() ? "头像识别失败，请稍后重试。" : "头像识别失败：" + detail;
    }

    private record Descriptor(
        float[] gray,
        float[] edge,
        float[] histogram,
        double mean,
        double contrast,
        double saturation
    ) {}
    private record Reference(
        int heroId,
        List<Descriptor> square,
        List<Descriptor> circular,
        List<Descriptor> squareCompact,
        List<Descriptor> circularCompact,
        Descriptor squareAnchor,
        Descriptor circularAnchor
    ) {}
    private record Library(String fingerprint, List<Reference> references) {}
    private record ScoredHero(int heroId, double score) {}
    private record ScoredReference(Reference reference, double score) {}
    private record PickGeometry(double centerFromEdge, double side, double yOffset, double spread) {}
    private record PickCandidate(PickGeometry geometry, double quality) {}
    private record CoarseEvidence(double score, int index, boolean left) {}
    private record BanGeometry(double firstFromEdge, double spacing, double centerY, double side) {
        private static final BanGeometry DEFAULT = new BanGeometry(0.130, 0.064, 0.045, 0.059);
    }

    private static final class SlotMatch {
        final BpScreenLayout.Slot slot;
        final int heroId;
        final double score;
        final double margin;
        final boolean accepted;

        SlotMatch(BpScreenLayout.Slot slot, int heroId, double score, double margin, boolean accepted) {
            this.slot = slot;
            this.heroId = heroId;
            this.score = score;
            this.margin = margin;
            this.accepted = accepted;
        }
    }
}
