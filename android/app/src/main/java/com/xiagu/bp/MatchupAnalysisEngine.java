package com.xiagu.bp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Signed evidence shared by recommendations and the draft report. No mechanic overrides. */
final class MatchupAnalysisEngine {
    static final String[] LANES = {"对抗路", "打野", "中路", "发育路", "游走"};
    static final int[][] COMBOS = {{2, 1}, {2, 4}, {0, 1}, {3, 4}};
    static final String[] COMBO_NAMES = {"中野", "中辅", "边野", "射辅"};

    record Evidence(boolean known, double value, int matches, int sources) {
        static Evidence missing() { return new Evidence(false, 0, 0, 0); }
        // The endpoint exposes significant relationships, not a complete win-rate matrix.
        String label() {
            if (!known) return "暂无数据";
            return value > 0.25 ? "我方有利" : value < -0.25 ? "我方不利" : "接近均势";
        }
        double reliableValue() {
            // Unknown sample sizes are not silently treated as well-supported evidence.
            return value * (matches > 0 ? Math.sqrt(matches / (matches + 80.0)) : 0.5);
        }
    }

    static Evidence counter(BpModels.Hero a, BpModels.Hero b, Map<Integer, BpModels.Analysis> data) {
        return relation(a, b, data, false);
    }

    static Evidence synergy(BpModels.Hero a, BpModels.Hero b, Map<Integer, BpModels.Analysis> data) {
        return relation(a, b, data, true);
    }

    private static Evidence relation(BpModels.Hero a, BpModels.Hero b,
                                      Map<Integer, BpModels.Analysis> data, boolean synergy) {
        if (a == null || b == null || a.id == b.id) return Evidence.missing();
        List<Evidence> found = new ArrayList<>();
        collect(data.get(a.id), b.name, synergy, 1, found);
        collect(data.get(b.id), a.name, synergy, synergy ? 1 : -1, found);
        if (found.isEmpty()) return Evidence.missing();
        double weighted = 0, weights = 0;
        int matches = 0;
        for (Evidence e : found) {
            double weight = Math.sqrt(Math.max(1, e.matches));
            weighted += e.value * weight;
            weights += weight;
            // Reverse endpoint rows often describe the SAME games. Never sum them.
            matches = Math.max(matches, e.matches);
        }
        return new Evidence(true, weighted / weights, matches, found.size());
    }

    private static void collect(BpModels.Analysis data, String name, boolean synergy,
                                int direction, List<Evidence> found) {
        if (data == null) return;
        collectRows(synergy ? data.goodSynergies : data.counters, name, direction, found);
        collectRows(synergy ? data.badSynergies : data.counteredBy, name, -direction, found);
    }

    private static void collectRows(List<BpModels.Relation> rows, String name, int sign, List<Evidence> found) {
        for (BpModels.Relation row : rows) {
            if (name.equals(row.heroName) && Double.isFinite(row.value)) {
                found.add(new Evidence(true, sign * Math.abs(row.value), Math.max(0, row.totalMatches), 1));
                break;
            }
        }
    }

    /** Global assignment avoids greedily using a flexible hero's only teammate-compatible lane. */
    static BpModels.Hero[] assignLanes(List<BpModels.Hero> heroes) {
        Assignment best = new Assignment();
        assign(heroes, 0, new BpModels.Hero[5], 0, best);
        return best.slots;
    }

    private static void assign(List<BpModels.Hero> heroes, int index, BpModels.Hero[] slots,
                               double score, Assignment best) {
        if (index == Math.min(heroes.size(), 5)) {
            if (score > best.score) { best.score = score; best.slots = slots.clone(); }
            return;
        }
        BpModels.Hero hero = heroes.get(index);
        for (int lane = 0; lane < 5; lane++) {
            if (slots[lane] != null || !hero.supports(LANES[lane])) continue;
            slots[lane] = hero;
            assign(heroes, index + 1, slots,
                score + 10 + (LANES[lane].equals(hero.tierRole) ? 1 : 0), best);
            slots[lane] = null;
        }
        // An unconventional comp remains partially unassigned, never fabricated into five lanes.
        assign(heroes, index + 1, slots, score, best);
    }

    static void moveToLane(BpModels.Hero[] slots, int target, BpModels.Hero hero) {
        BpModels.Hero previous = slots[target];
        if (hero != null) for (int i = 0; i < slots.length; i++) {
            if (i != target && slots[i] != null && slots[i].id == hero.id) slots[i] = previous;
        }
        slots[target] = hero;
    }

    static List<BpModels.Hero> unassigned(List<BpModels.Hero> heroes, BpModels.Hero[] slots) {
        List<BpModels.Hero> result = new ArrayList<>();
        for (BpModels.Hero hero : heroes) {
            if (Arrays.stream(slots).noneMatch(h -> h != null && h.id == hero.id)) result.add(hero);
        }
        return result;
    }

    private static final class Assignment {
        double score = -1;
        BpModels.Hero[] slots = new BpModels.Hero[5];
    }
}
