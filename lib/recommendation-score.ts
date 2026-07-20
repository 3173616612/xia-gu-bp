export type RecommendationWeights = {
  counterLift: number;
  matchup: number;
  synergy: number;
  tier: number;
};

const clampScore = (value: number) => Math.min(100, Math.max(0, value));

export type WeightedRelationship = {
  value: number;
  weight?: number;
};

export type RelationshipBreakdown = {
  score: number;
  positiveBonus: number;
  negativePenalty: number;
};

/**
 * The upstream tier score spans almost the full 0-100 range. Compressing it to
 * 40-70 keeps tier meaningful without allowing a T0 label to erase matchup data.
 */
export function compressTierScore(rawTierScore: number) {
  return Math.round(40 + clampScore(rawTierScore) * 0.3);
}

/**
 * Measures how much the current enemy lineup improves a hero relative to its
 * normal meta baseline. This prevents high-tier heroes from receiving the same
 * strength twice through both tier and matchup inputs.
 */
export function calculateCounterLiftScore(matchupScore: number, tierScore: number) {
  const safeMatchup = clampScore(matchupScore);
  if (safeMatchup <= 50) return Math.round(safeMatchup);
  return Math.round(clampScore(50 + (safeMatchup - clampScore(tierScore)) * 1.5));
}

/**
 * Scores both relationship strength and breadth. Callers should include a
 * neutral (value 0) entry for every analyzed hero without a significant link,
 * so countering one opponent cannot score the same as countering all five.
 */
export function calculateRelationshipBreakdown(
  relations: WeightedRelationship[],
  strengthScale: number,
): RelationshipBreakdown {
  if (!relations.length) return { score: 50, positiveBonus: 0, negativePenalty: 0 };

  let positiveStrength = 0;
  let negativeStrength = 0;
  let totalWeight = 0;
  let positiveWeight = 0;
  let negativeWeight = 0;

  relations.forEach(({ value, weight = 1 }) => {
    const safeWeight = Math.max(0, weight);
    totalWeight += safeWeight;
    if (value > 0) {
      positiveStrength += value * safeWeight;
      positiveWeight += safeWeight;
    }
    if (value < 0) {
      negativeStrength += Math.abs(value) * safeWeight;
      negativeWeight += safeWeight;
    }
  });

  if (!totalWeight) return { score: 50, positiveBonus: 0, negativePenalty: 0 };

  const positiveBonus = positiveStrength / totalWeight * strengthScale
    + positiveWeight / totalWeight * 8;
  const negativePenalty = negativeStrength / totalWeight * strengthScale * 1.15
    + negativeWeight / totalWeight * 10;

  return {
    score: Math.round(clampScore(50 + positiveBonus - negativePenalty)),
    positiveBonus: Math.round(positiveBonus),
    negativePenalty: Math.round(negativePenalty),
  };
}

export function calculateRelationshipScore(relations: WeightedRelationship[], strengthScale: number) {
  return calculateRelationshipBreakdown(relations, strengthScale).score;
}

/** Relationship evidence receives most of the weight whenever the user supplies it. */
export function recommendationWeights(enemyCount: number, allyCount: number): RecommendationWeights {
  if (enemyCount >= 4 && allyCount > 0) return { counterLift: 0.3, matchup: 0.25, synergy: 0.3, tier: 0.15 };
  if (enemyCount >= 4) return { counterLift: 0.55, matchup: 0.3, synergy: 0, tier: 0.15 };
  if (enemyCount > 0 && allyCount > 0) return { counterLift: 0.25, matchup: 0.3, synergy: 0.25, tier: 0.2 };
  if (enemyCount > 0) return { counterLift: 0.35, matchup: 0.4, synergy: 0, tier: 0.25 };
  if (allyCount > 0) return { counterLift: 0, matchup: 0, synergy: 0.65, tier: 0.35 };
  return { counterLift: 0, matchup: 0, synergy: 0, tier: 1 };
}

export function calculateRecommendationScore(input: {
  matchupScore: number;
  counterLiftScore: number;
  synergyScore: number;
  tierScore: number;
  enemyCount: number;
  allyCount: number;
}) {
  const weights = recommendationWeights(input.enemyCount, input.allyCount);
  const score = Math.round(
    clampScore(input.counterLiftScore) * weights.counterLift
      + clampScore(input.matchupScore) * weights.matchup
      + clampScore(input.synergyScore) * weights.synergy
      + clampScore(input.tierScore) * weights.tier,
  );
  return { score, weights };
}
