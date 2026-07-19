export type RecommendationWeights = {
  counter: number;
  synergy: number;
  tier: number;
};

const clampScore = (value: number) => Math.min(100, Math.max(0, value));

export type WeightedRelationship = {
  value: number;
  weight?: number;
};

/**
 * The upstream tier score spans almost the full 0-100 range. Compressing it to
 * 40-70 keeps tier meaningful without allowing a T0 label to erase matchup data.
 */
export function compressTierScore(rawTierScore: number) {
  return Math.round(40 + clampScore(rawTierScore) * 0.3);
}

/**
 * Scores both relationship strength and breadth. Callers should include a
 * neutral (value 0) entry for every analyzed hero without a significant link,
 * so countering one opponent cannot score the same as countering all five.
 */
export function calculateRelationshipScore(relations: WeightedRelationship[], strengthScale: number) {
  if (!relations.length) return 50;

  let weightedValue = 0;
  let totalWeight = 0;
  let positiveWeight = 0;
  let negativeWeight = 0;

  relations.forEach(({ value, weight = 1 }) => {
    const safeWeight = Math.max(0, weight);
    weightedValue += value * safeWeight;
    totalWeight += safeWeight;
    if (value > 0) positiveWeight += safeWeight;
    if (value < 0) negativeWeight += safeWeight;
  });

  if (!totalWeight) return 50;
  const averageStrength = weightedValue / totalWeight;
  const breadth = (positiveWeight - negativeWeight) / totalWeight;
  return Math.round(clampScore(50 + averageStrength * strengthScale + breadth * 8));
}

/** Relationship evidence receives most of the weight whenever the user supplies it. */
export function recommendationWeights(enemyCount: number, allyCount: number): RecommendationWeights {
  if (enemyCount > 0 && allyCount > 0) return { counter: 0.5, synergy: 0.3, tier: 0.2 };
  if (enemyCount > 0) return { counter: 0.7, synergy: 0, tier: 0.3 };
  if (allyCount > 0) return { counter: 0, synergy: 0.6, tier: 0.4 };
  return { counter: 0, synergy: 0, tier: 1 };
}

export function calculateRecommendationScore(input: {
  counterScore: number;
  synergyScore: number;
  tierScore: number;
  enemyCount: number;
  allyCount: number;
}) {
  const weights = recommendationWeights(input.enemyCount, input.allyCount);
  const score = Math.round(
    clampScore(input.counterScore) * weights.counter
      + clampScore(input.synergyScore) * weights.synergy
      + clampScore(input.tierScore) * weights.tier,
  );
  return { score, weights };
}
