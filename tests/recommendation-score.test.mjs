import assert from "node:assert/strict";
import test from "node:test";

import {
  calculateCounterLiftScore,
  calculateRelationshipBreakdown,
  calculateRelationshipScore,
  calculateRecommendationScore,
  compressTierScore,
} from "../lib/recommendation-score.ts";

test("compresses the upstream tier range so T0 cannot dominate relationships", () => {
  assert.equal(compressTierScore(0), 40);
  assert.equal(compressTierScore(50), 55);
  assert.equal(compressTierScore(100), 70);
});

test("counter lift measures situational strength against the tier baseline", () => {
  assert.equal(calculateCounterLiftScore(54, 40), 71);
  assert.equal(calculateCounterLiftScore(69, 69), 50);
  assert.equal(calculateCounterLiftScore(50, 40), 50);
  assert.equal(calculateCounterLiftScore(46, 40), 46);
});

test("rewards counter breadth across the complete enemy lineup", () => {
  const countersOne = calculateRelationshipScore([
    { value: 2 }, { value: 0 }, { value: 0 }, { value: 0 }, { value: 0 },
  ], 4);
  const countersFive = calculateRelationshipScore([
    { value: 2 }, { value: 2 }, { value: 2 }, { value: 2 }, { value: 2 },
  ], 4);

  assert.ok(countersFive > countersOne, `${countersFive} should beat ${countersOne}`);
});

test("enemy disadvantages are explicit penalties below the neutral score", () => {
  const breakdown = calculateRelationshipBreakdown(
    Array.from({ length: 5 }, () => ({ value: -2 })),
    4,
  );

  assert.ok(breakdown.negativePenalty > 0);
  assert.equal(breakdown.positiveBonus, 0);
  assert.ok(breakdown.score < 50);
});

test("bad teammate pairings materially reduce the recommendation score", () => {
  const neutralTeam = calculateRecommendationScore({
    matchupScore: 60,
    counterLiftScore: 58,
    synergyScore: 50,
    tierScore: 55,
    enemyCount: 5,
    allyCount: 4,
  });
  const badSynergy = calculateRelationshipBreakdown(
    Array.from({ length: 4 }, () => ({ value: -3 })),
    4.2,
  );
  const conflictingTeam = calculateRecommendationScore({
    matchupScore: 60,
    counterLiftScore: 58,
    synergyScore: badSynergy.score,
    tierScore: 55,
    enemyCount: 5,
    allyCount: 4,
  });

  assert.ok(badSynergy.negativePenalty > 0);
  assert.ok(conflictingTeam.score < neutralTeam.score);
});

test("five-enemy counter evidence can outrank a neutral T0 candidate", () => {
  const neutralT0Tier = compressTierScore(95);
  const counterPickTier = compressTierScore(35);
  const counterPickMatchup = calculateRelationshipScore(Array.from({ length: 5 }, () => ({ value: 2 })), 4);
  const neutralT0 = calculateRecommendationScore({
    matchupScore: 50,
    counterLiftScore: calculateCounterLiftScore(50, neutralT0Tier),
    synergyScore: 50,
    tierScore: neutralT0Tier,
    enemyCount: 5,
    allyCount: 4,
  });
  const counterPick = calculateRecommendationScore({
    matchupScore: counterPickMatchup,
    counterLiftScore: calculateCounterLiftScore(counterPickMatchup, counterPickTier),
    synergyScore: 60,
    tierScore: counterPickTier,
    enemyCount: 5,
    allyCount: 4,
  });

  assert.deepEqual(counterPick.weights, { counterLift: 0.3, matchup: 0.25, synergy: 0.3, tier: 0.15 });
  assert.ok(counterPick.score > neutralT0.score, `${counterPick.score} should beat ${neutralT0.score}`);
});

test("the supplied five-enemy example ranks Zhuangzhou above a generic T0 support", () => {
  const zhuangzhou = calculateRecommendationScore({
    matchupScore: 54,
    counterLiftScore: calculateCounterLiftScore(54, 40),
    synergyScore: 50,
    tierScore: 40,
    enemyCount: 5,
    allyCount: 0,
  });
  const genericT0 = calculateRecommendationScore({
    matchupScore: 69,
    counterLiftScore: calculateCounterLiftScore(69, 69),
    synergyScore: 50,
    tierScore: 69,
    enemyCount: 5,
    allyCount: 0,
  });

  assert.deepEqual(zhuangzhou.weights, { counterLift: 0.55, matchup: 0.3, synergy: 0, tier: 0.15 });
  assert.equal(zhuangzhou.score, 61);
  assert.equal(genericT0.score, 59);
  assert.ok(zhuangzhou.score > genericT0.score);
});

test("teammate synergy materially changes otherwise equal recommendations", () => {
  const strongFit = calculateRecommendationScore({
    matchupScore: 60,
    counterLiftScore: 58,
    synergyScore: 80,
    tierScore: 55,
    enemyCount: 5,
    allyCount: 4,
  });
  const weakFit = calculateRecommendationScore({
    matchupScore: 60,
    counterLiftScore: 58,
    synergyScore: 30,
    tierScore: 55,
    enemyCount: 5,
    allyCount: 4,
  });

  assert.equal(strongFit.score - weakFit.score, 15);
});
