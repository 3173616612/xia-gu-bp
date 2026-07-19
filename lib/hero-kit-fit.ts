const CONTROL_PRESSURE: Record<string, number> = {
  关羽: 2.8,
  "元流之子(坦克)": 2.5,
  武则天: 3,
  后羿: 1.2,
  鲁班大师: 3,
  白起: 3,
  廉颇: 2.8,
  项羽: 2.7,
  张飞: 2.5,
  牛魔: 2.8,
  鬼谷子: 2.7,
  东皇太一: 3,
  太乙真人: 2.3,
  苏烈: 2.8,
  钟馗: 2.6,
  盾山: 2.8,
  墨子: 2.7,
  刘禅: 2.5,
  甄姬: 2.8,
  王昭君: 2.8,
  金蝉: 2.7,
  海月: 2.4,
  张良: 3,
  妲己: 2.2,
  小乔: 2,
  安琪拉: 2.1,
  西施: 2.5,
  弈星: 2.4,
  钟无艳: 2.6,
  夏侯惇: 2.2,
  孙策: 2.7,
  达摩: 2.7,
};

export type TacticalFit = {
  bonus: number;
  controlPressure: number;
  reason: string | null;
};

/**
 * Pairwise win data misses kit-level interactions such as team-wide cleanse.
 * This narrow correction models only high-confidence mechanics and remains
 * subordinate to the live top-1000 matchup data.
 */
export function calculateTacticalFit(candidateName: string, enemyNames: string[]): TacticalFit {
  if (candidateName !== "庄周" || !enemyNames.length) {
    return { bonus: 0, controlPressure: 0, reason: null };
  }

  const controlPressure = enemyNames.reduce(
    (sum, name) => sum + (CONTROL_PRESSURE[name] ?? 0),
    0,
  ) / (enemyNames.length * 3);
  const bonus = Math.round(Math.min(1, controlPressure) * 34);

  return {
    bonus,
    controlPressure,
    reason: bonus >= 12 ? `敌方控制密度 ${Math.round(controlPressure * 100)}%，团队解控机制补正 +${bonus}` : null,
  };
}
