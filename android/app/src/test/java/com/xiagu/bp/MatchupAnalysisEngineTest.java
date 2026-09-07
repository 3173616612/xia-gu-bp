package com.xiagu.bp;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;
import java.util.Map;

public class MatchupAnalysisEngineTest {
    @Test public void counterDirectionIsAntisymmetricFromEitherEndpoint() {
        var a = hero(1, "甲", "中路"); var b = hero(2, "乙", "打野");
        var data = analysis(1); data.counters.add(relation("乙", 4, 200));
        var map = Map.of(1, data);
        assertEquals(4, MatchupAnalysisEngine.counter(a, b, map).value(), 1e-8);
        assertEquals(-4, MatchupAnalysisEngine.counter(b, a, map).value(), 1e-8);
    }
    @Test public void negativeEnemyAndNegativeAllyRelationsAreNotDropped() {
        var a = hero(1, "甲", "中路"); var b = hero(2, "乙", "打野");
        var data = analysis(1); data.counteredBy.add(relation("乙", -5, 120)); data.badSynergies.add(relation("乙", -3, 90));
        assertEquals(-5, MatchupAnalysisEngine.counter(a, b, Map.of(1, data)).value(), 1e-8);
        assertEquals(-3, MatchupAnalysisEngine.synergy(b, a, Map.of(1, data)).value(), 1e-8);
    }
    @Test public void reverseEndpointsDoNotDoubleCountGames() {
        var a = hero(1, "甲", "中路"); var b = hero(2, "乙", "打野");
        var x = analysis(1); var y = analysis(2);
        x.counters.add(relation("乙", 4, 200)); y.counteredBy.add(relation("甲", 4, 200));
        var e = MatchupAnalysisEngine.counter(a, b, Map.of(1, x, 2, y));
        assertEquals(4, e.value(), 1e-8); assertEquals(200, e.matches()); assertEquals(2, e.sources());
    }
    @Test public void missingIsNotNeutralAndInvalidNumbersAreIgnored() {
        var a = hero(1, "甲", "中路"); var b = hero(2, "乙", "打野"); var x = analysis(1);
        x.counters.add(relation("乙", Double.NaN, 100));
        assertFalse(MatchupAnalysisEngine.counter(a, b, Map.of(1, x)).known());
        assertEquals("暂无数据", MatchupAnalysisEngine.counter(null, b, Map.of()).label());
    }
    @Test public void smallSamplesAreShrunkMoreThanLargerSamples() {
        var small = new MatchupAnalysisEngine.Evidence(true, 6, 10, 1);
        var large = new MatchupAnalysisEngine.Evidence(true, 6, 500, 1);
        assertTrue(small.reliableValue() < large.reliableValue());
        assertTrue(large.reliableValue() < 6);
    }
    @Test public void globalAssignmentReservesOnlyAvailableLaneForSingleRoleHero() {
        var flex = hero(1, "灵活", "中路", "游走"); flex.tierRole = "中路";
        var mage = hero(2, "法师", "中路");
        var slots = MatchupAnalysisEngine.assignLanes(List.of(flex, mage));
        assertEquals(mage, slots[2]); assertEquals(flex, slots[4]);
    }
    @Test public void unusualCompsAreNotForcedIntoUnsupportedLanes() {
        var a = hero(1, "甲", "中路"); var b = hero(2, "乙", "中路");
        var slots = MatchupAnalysisEngine.assignLanes(List.of(a, b));
        assertEquals(1, MatchupAnalysisEngine.unassigned(List.of(a, b), slots).size());
        assertNull(slots[0]); assertNull(slots[1]); assertNull(slots[3]); assertNull(slots[4]);
    }
    @Test public void userCanSwapRolesIncludingUnconventionalRolesWithoutDuplicates() {
        var a = hero(1, "甲", "中路"); var b = hero(2, "乙", "游走");
        var slots = MatchupAnalysisEngine.assignLanes(List.of(a, b));
        MatchupAnalysisEngine.moveToLane(slots, 2, b);
        assertEquals(b, slots[2]); assertEquals(a, slots[4]);
        MatchupAnalysisEngine.moveToLane(slots, 0, a);
        assertEquals(a, slots[0]); assertNull(slots[4]);
    }
    @Test public void fiveLanesAndFourRequestedCombinationsAreExact() {
        assertEquals(5, MatchupAnalysisEngine.LANES.length);
        assertArrayEquals(new String[]{"中野", "中辅", "边野", "射辅"}, MatchupAnalysisEngine.COMBO_NAMES);
        assertArrayEquals(new int[]{2, 1}, MatchupAnalysisEngine.COMBOS[0]);
        assertArrayEquals(new int[]{2, 4}, MatchupAnalysisEngine.COMBOS[1]);
        assertArrayEquals(new int[]{0, 1}, MatchupAnalysisEngine.COMBOS[2]);
        assertArrayEquals(new int[]{3, 4}, MatchupAnalysisEngine.COMBOS[3]);
    }
    private BpModels.Hero hero(int id, String name, String... positions) {
        var h = new BpModels.Hero(); h.id = id; h.name = name; h.positions.addAll(List.of(positions)); return h;
    }
    private BpModels.Analysis analysis(int id) {var a = new BpModels.Analysis(); a.heroId = id; return a;}
    private BpModels.Relation relation(String name, double value, int matches) {
        var r = new BpModels.Relation(); r.heroName = name; r.value = value; r.totalMatches = matches; return r;
    }
}
