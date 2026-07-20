import assert from "node:assert/strict";
import test from "node:test";

import {
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
    counterScore: 60,
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
    counterScore: 60,
    synergyScore: badSynergy.score,
    tierScore: 55,
    enemyCount: 5,
    allyCount: 4,
  });

  assert.ok(badSynergy.negativePenalty > 0);
  assert.ok(conflictingTeam.score < neutralTeam.score);
});

test("five-enemy counter evidence can outrank a neutral T0 candidate", () => {
  const neutralT0 = calculateRecommendationScore({
    counterScore: 50,
    synergyScore: 50,
    tierScore: compressTierScore(95),
    enemyCount: 5,
    allyCount: 4,
  });
  const counterPick = calculateRecommendationScore({
    counterScore: calculateRelationshipScore(Array.from({ length: 5 }, () => ({ value: 2 })), 4),
    synergyScore: 60,
    tierScore: compressTierScore(35),
    enemyCount: 5,
    allyCount: 4,
  });

  assert.deepEqual(counterPick.weights, { counter: 0.5, synergy: 0.3, tier: 0.2 });
  assert.ok(counterPick.score > neutralT0.score, `${counterPick.score} should beat ${neutralT0.score}`);
});

test("teammate synergy materially changes otherwise equal recommendations", () => {
  const strongFit = calculateRecommendationScore({
    counterScore: 60,
    synergyScore: 80,
    tierScore: 55,
    enemyCount: 5,
    allyCount: 4,
  });
  const weakFit = calculateRecommendationScore({
    counterScore: 60,
    synergyScore: 30,
    tierScore: 55,
    enemyCount: 5,
    allyCount: 4,
  });

  assert.equal(strongFit.score - weakFit.score, 15);
});
