package com.xiagu.bp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RecommendationEngineTest {
    @Test
    public void multiPositionHeroUsesSoftProbabilitiesInsteadOfOneForcedLane() {
        BpModels.Hero hero = hero(1, "多位置英雄", 50, "中路", "中路", "游走", "打野");

        assertEquals(0.6, RecommendationEngine.positionProbability(hero, "中路"), 0.0001);
        assertEquals(0.2, RecommendationEngine.positionProbability(hero, "游走"), 0.0001);
        assertEquals(0.2, RecommendationEngine.positionProbability(hero, "打野"), 0.0001);
        assertEquals(0, RecommendationEngine.positionProbability(hero, "发育路"), 0.0001);
    }

    @Test
    public void neutralAndLosingMatchupsDoNotGainLiftFromLowTier() {
        assertEquals(50, RecommendationEngine.counterLiftScore(50, 40));
        assertEquals(46, RecommendationEngine.counterLiftScore(46, 40));
        assertEquals(71, RecommendationEngine.counterLiftScore(54, 40));
    }

    @Test
    public void abnormalSixEnemyLineupUsesAllEvidenceAndStillHonorsBans() {
        BpModels.Hero situational = hero(100, "情境英雄", 5, "游走", "游走");
        BpModels.Hero generic = hero(101, "常规英雄", 95, "游走", "游走");
        List<BpModels.Hero> roster = new ArrayList<>(List.of(situational, generic));
        BpModels.DetectedLineup lineup = new BpModels.DetectedLineup();
        Map<Integer, BpModels.Analysis> analyses = new LinkedHashMap<>();

        for (int i = 0; i < 6; i++) {
            BpModels.Hero enemy = hero(200 + i, "敌方" + i, 50, "中路", "中路", "游走");
            lineup.enemies.add(enemy);
            roster.add(enemy);
            BpModels.Analysis analysis = new BpModels.Analysis();
            analysis.heroId = enemy.id;
            BpModels.Relation favorable = new BpModels.Relation();
            favorable.heroName = situational.name;
            favorable.value = 2.5;
            favorable.totalMatches = 300;
            analysis.counteredBy.add(favorable);
            analyses.put(enemy.id, analysis);
        }

        List<BpModels.Recommendation> recommendations = RecommendationEngine.recommend(roster, analyses, lineup, "游走");
        assertFalse(recommendations.isEmpty());
        assertEquals("情境英雄", recommendations.get(0).hero.name);
        assertTrue(recommendations.get(0).score > recommendations.get(1).score);

        lineup.bans.add(situational);
        recommendations = RecommendationEngine.recommend(roster, analyses, lineup, "游走");
        assertEquals(1, recommendations.size());
        assertEquals("常规英雄", recommendations.get(0).hero.name);
    }

    @Test
    public void suppliedFiveEnemyExampleRanksZhuangzhouFirst() {
        BpModels.Hero zhuangzhou = hero(113, "庄周", 1, "游走", "游走");
        BpModels.Hero genericT0 = hero(509, "通用T0辅助", 96, "游走", "游走");
        List<BpModels.Hero> roster = new ArrayList<>(List.of(zhuangzhou, genericT0));
        BpModels.DetectedLineup lineup = new BpModels.DetectedLineup();
        Map<Integer, BpModels.Analysis> analyses = new LinkedHashMap<>();
        String[] names = {"关羽", "元流之子(坦克)", "武则天", "后羿", "鲁班大师"};

        for (int i = 0; i < names.length; i++) {
            BpModels.Hero enemy = hero(300 + i, names[i], 50, "中路", "中路", "游走");
            lineup.enemies.add(enemy);
            roster.add(enemy);
            BpModels.Analysis analysis = new BpModels.Analysis();
            analysis.heroId = enemy.id;
            BpModels.Relation favorable = new BpModels.Relation();
            favorable.heroName = zhuangzhou.name;
            favorable.value = 1.2;
            favorable.totalMatches = 250;
            analysis.counteredBy.add(favorable);
            analyses.put(enemy.id, analysis);
        }

        List<BpModels.Recommendation> recommendations = RecommendationEngine.recommend(roster, analyses, lineup, "游走");
        assertEquals("庄周", recommendations.get(0).hero.name);
        assertTrue(recommendations.get(0).score > recommendations.get(1).score);
        assertTrue(recommendations.get(0).summary.contains("对敌"));
        assertTrue(recommendations.get(0).summary.contains("配合"));
        assertTrue(recommendations.get(0).summary.contains("梯度"));
    }

    @Test
    public void dismissedHeroIsRemovedAndNextRankedHeroMovesUp() {
        List<BpModels.Hero> roster = new ArrayList<>(List.of(
            hero(1, "第一顺位", 95, "游走", "游走"),
            hero(2, "第二顺位", 80, "游走", "游走"),
            hero(3, "第三顺位", 65, "游走", "游走"),
            hero(4, "第四顺位", 50, "游走", "游走")
        ));
        BpModels.DetectedLineup lineup = new BpModels.DetectedLineup();
        Map<Integer, BpModels.Analysis> analyses = new LinkedHashMap<>();

        List<BpModels.Recommendation> original = RecommendationEngine.recommend(
            roster,
            analyses,
            lineup,
            "游走"
        );
        assertEquals("第一顺位", original.get(0).hero.name);
        assertEquals("第二顺位", original.get(1).hero.name);
        assertEquals("第三顺位", original.get(2).hero.name);

        HashSet<Integer> dismissed = new HashSet<>();
        dismissed.add(original.get(0).hero.id);
        List<BpModels.Recommendation> updated = RecommendationEngine.recommend(
            roster,
            analyses,
            lineup,
            "游走",
            dismissed
        );

        assertEquals("第二顺位", updated.get(0).hero.name);
        assertEquals("第三顺位", updated.get(1).hero.name);
        assertEquals("第四顺位", updated.get(2).hero.name);
    }

    private static BpModels.Hero hero(int id, String name, double tierScore, String tierRole, String... positions) {
        BpModels.Hero hero = new BpModels.Hero();
        hero.id = id;
        hero.name = name;
        hero.tierScore = tierScore;
        hero.tierRole = tierRole;
        hero.tier = tierScore > 80 ? "T0" : tierScore < 20 ? "T4" : "T2";
        hero.positions.addAll(List.of(positions));
        return hero;
    }
}
