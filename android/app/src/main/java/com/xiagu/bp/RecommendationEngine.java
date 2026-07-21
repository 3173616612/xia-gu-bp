package com.xiagu.bp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RecommendationEngine {
    private RecommendationEngine() {}

    static List<BpModels.Recommendation> recommend(
        List<BpModels.Hero> roster,
        Map<Integer, BpModels.Analysis> analyses,
        BpModels.DetectedLineup lineup,
        String targetLane
    ) {
        Set<Integer> excluded = new HashSet<>();
        for (BpModels.Hero hero : lineup.allies) excluded.add(hero.id);
        for (BpModels.Hero hero : lineup.enemies) excluded.add(hero.id);
        for (BpModels.Hero hero : lineup.bans) excluded.add(hero.id);

        List<BpModels.Recommendation> output = new ArrayList<>();
        for (BpModels.Hero candidate : roster) {
            if (!candidate.supports(targetLane) || excluded.contains(candidate.id)) continue;

            List<WeightedRelation> matchupRelations = new ArrayList<>();
            int sampleTotal = 0;
            for (BpModels.Hero enemy : lineup.enemies) {
                BpModels.Analysis analysis = analyses.get(enemy.id);
                double value = 0;
                if (analysis != null) {
                    BpModels.Relation favorable = find(analysis.counteredBy, candidate.name);
                    BpModels.Relation unfavorable = find(analysis.counters, candidate.name);
                    if (favorable != null) {
                        value = Math.abs(favorable.value);
                        sampleTotal += favorable.totalMatches;
                    } else if (unfavorable != null) {
                        value = -Math.abs(unfavorable.value);
                        sampleTotal += unfavorable.totalMatches;
                    }
                }
                double weight = 0.9 + 0.25 * positionProbability(enemy, targetLane);
                matchupRelations.add(new WeightedRelation(value, weight));
            }

            List<WeightedRelation> synergyRelations = new ArrayList<>();
            for (BpModels.Hero ally : lineup.allies) {
                BpModels.Analysis analysis = analyses.get(ally.id);
                double value = 0;
                if (analysis != null) {
                    BpModels.Relation good = find(analysis.goodSynergies, candidate.name);
                    BpModels.Relation bad = find(analysis.badSynergies, candidate.name);
                    if (good != null) {
                        value = Math.abs(good.value);
                        sampleTotal += good.totalMatches;
                    } else if (bad != null) {
                        value = -Math.abs(bad.value);
                        sampleTotal += bad.totalMatches;
                    }
                }
                synergyRelations.add(new WeightedRelation(value, 1));
            }

            BpModels.RelationshipBreakdown matchup = relationshipBreakdown(matchupRelations, 4);
            BpModels.RelationshipBreakdown synergy = relationshipBreakdown(synergyRelations, 4.2);
            int rawTier = (int) Math.round(clamp(candidate.tierScore == null ? tierFallback(candidate.tier) : candidate.tierScore, 0, 100));
            int tierScore = compressTierScore(rawTier);
            int counterLift = counterLiftScore(matchup.score, tierScore);
            Weights weights = weights(lineup.enemies.size(), lineup.allies.size());
            int total = (int) Math.round(
                counterLift * weights.counterLift
                    + matchup.score * weights.matchup
                    + synergy.score * weights.synergy
                    + tierScore * weights.tier
            );

            BpModels.Recommendation value = new BpModels.Recommendation();
            value.hero = candidate;
            value.score = total;
            value.counterLiftScore = counterLift;
            value.matchupScore = matchup.score;
            value.synergyScore = synergy.score;
            value.tierScore = tierScore;
            value.negativePenalty = matchup.negativePenalty + synergy.negativePenalty;
            value.secondaryRole = !targetLane.equals(candidate.tierRole) && candidate.positions.size() > 1;
            String roleNote = value.secondaryRole ? " · 多位置" : "";
            String tierLabel = candidate.tier == null || candidate.tier.isBlank()
                ? String.valueOf(tierScore)
                : candidate.tier + "/" + tierScore;
            String negativeNote = value.negativePenalty > 0 ? " · 负向-" + value.negativePenalty : "";
            String confidence = sampleTotal >= 800 ? "高" : sampleTotal >= 180 ? "中" : "低";
            value.summary = "对敌 " + matchup.score + " · 配合 " + synergy.score
                + " · 梯度 " + tierLabel + " · 提升 " + counterLift
                + negativeNote + roleNote + " · 置信度" + confidence;
            output.add(value);
        }

        output.sort(Comparator
            .comparingInt((BpModels.Recommendation value) -> value.score).reversed()
            .thenComparing(Comparator.comparingInt((BpModels.Recommendation value) -> value.counterLiftScore).reversed())
            .thenComparing(Comparator.comparingInt((BpModels.Recommendation value) -> value.matchupScore + value.synergyScore).reversed())
            .thenComparing(Comparator.comparingInt((BpModels.Recommendation value) -> value.tierScore).reversed()));
        return output.size() > 5 ? new ArrayList<>(output.subList(0, 5)) : output;
    }

    static double positionProbability(BpModels.Hero hero, String lane) {
        if (!hero.positions.contains(lane) || hero.positions.isEmpty()) return 0;
        if (hero.positions.size() == 1) return 1;
        if (hero.positions.contains(hero.tierRole)) {
            if (lane.equals(hero.tierRole)) return 0.6;
            return 0.4 / (hero.positions.size() - 1);
        }
        return 1.0 / hero.positions.size();
    }

    static int compressTierScore(double rawTierScore) {
        return (int) Math.round(40 + clamp(rawTierScore, 0, 100) * 0.3);
    }

    static int counterLiftScore(double matchupScore, double tierScore) {
        double safeMatchup = clamp(matchupScore, 0, 100);
        if (safeMatchup <= 50) return (int) Math.round(safeMatchup);
        return (int) Math.round(clamp(50 + (safeMatchup - clamp(tierScore, 0, 100)) * 1.5, 0, 100));
    }

    private static BpModels.RelationshipBreakdown relationshipBreakdown(List<WeightedRelation> relations, double strengthScale) {
        BpModels.RelationshipBreakdown output = new BpModels.RelationshipBreakdown();
        if (relations.isEmpty()) {
            output.score = 50;
            return output;
        }
        double positiveStrength = 0;
        double negativeStrength = 0;
        double totalWeight = 0;
        double positiveWeight = 0;
        double negativeWeight = 0;
        for (WeightedRelation relation : relations) {
            double weight = Math.max(0, relation.weight);
            totalWeight += weight;
            if (relation.value > 0) {
                positiveStrength += relation.value * weight;
                positiveWeight += weight;
            } else if (relation.value < 0) {
                negativeStrength += Math.abs(relation.value) * weight;
                negativeWeight += weight;
            }
        }
        if (totalWeight == 0) {
            output.score = 50;
            return output;
        }
        double bonus = positiveStrength / totalWeight * strengthScale + positiveWeight / totalWeight * 8;
        double penalty = negativeStrength / totalWeight * strengthScale * 1.15 + negativeWeight / totalWeight * 10;
        output.positiveBonus = (int) Math.round(bonus);
        output.negativePenalty = (int) Math.round(penalty);
        output.score = (int) Math.round(clamp(50 + bonus - penalty, 0, 100));
        return output;
    }

    private static BpModels.Relation find(List<BpModels.Relation> relations, String heroName) {
        for (BpModels.Relation relation : relations) {
            if (heroName.equals(relation.heroName)) return relation;
        }
        return null;
    }

    private static Weights weights(int enemyCount, int allyCount) {
        if (enemyCount >= 4 && allyCount > 0) return new Weights(0.3, 0.25, 0.3, 0.15);
        if (enemyCount >= 4) return new Weights(0.55, 0.3, 0, 0.15);
        if (enemyCount > 0 && allyCount > 0) return new Weights(0.25, 0.3, 0.25, 0.2);
        if (enemyCount > 0) return new Weights(0.35, 0.4, 0, 0.25);
        if (allyCount > 0) return new Weights(0, 0, 0.65, 0.35);
        return new Weights(0, 0, 0, 1);
    }

    private static double tierFallback(String tier) {
        if (tier == null) return 50;
        return switch (tier) {
            case "T0" -> 90;
            case "T1" -> 72;
            case "T2" -> 55;
            case "T3" -> 38;
            case "T4" -> 20;
            default -> 50;
        };
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    private static final class WeightedRelation {
        final double value;
        final double weight;

        WeightedRelation(double value, double weight) {
            this.value = value;
            this.weight = weight;
        }
    }

    private static final class Weights {
        final double counterLift;
        final double matchup;
        final double synergy;
        final double tier;

        Weights(double counterLift, double matchup, double synergy, double tier) {
            this.counterLift = counterLift;
            this.matchup = matchup;
            this.synergy = synergy;
            this.tier = tier;
        }
    }
}
