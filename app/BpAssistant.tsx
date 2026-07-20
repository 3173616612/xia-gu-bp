"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import {
  calculateCounterLiftScore,
  calculateRelationshipBreakdown,
  calculateRecommendationScore,
  compressTierScore,
  type RecommendationWeights,
} from "@/lib/recommendation-score";

const POSITIONS = ["对抗路", "打野", "中路", "发育路", "游走"] as const;
type Position = (typeof POSITIONS)[number];
type TeamSide = "ally" | "enemy";
type MainView = "recommend" | "counter";

type Hero = {
  id: number;
  name: string;
  avatarUrl: string;
  roles: string;
  positions?: Position[];
  tierScore?: number | null;
  tier?: string | null;
  tierRole?: string | null;
  rankInRole?: number | null;
  highBan?: boolean;
  lowPick?: boolean;
};

type Relation = {
  heroName: string;
  totalMatches: number;
  advantageIndex?: number;
  synergyIndex?: number;
};

type Analysis = {
  heroId: number;
  counters: Relation[];
  counteredBy: Relation[];
  goodSynergies: Relation[];
  badSynergies: Relation[];
  fetchedAt?: string;
};

type MetaResponse = {
  heroes: Hero[];
  source: string;
  sourceUrl?: string;
  fetchedAt: string;
  queryDate: string;
  mode?: string;
  isFallback?: boolean;
  fallbackDays?: number;
  fallback?: { used: boolean; days: number };
};

type Lineup = Record<Position, Hero | null>;

type PickerContext =
  | { mode: "lineup"; side: TeamSide; position: Position }
  | { mode: "counter" }
  | { mode: "ban" };

type MatchEvidence = {
  enemy: string;
  value: number;
  matches: number;
  sameLane: boolean;
};

type SynergyEvidence = {
  ally: string;
  value: number;
  matches: number;
};

type Recommendation = {
  hero: Hero;
  score: number;
  counterLiftScore: number;
  matchupScore: number;
  tierScore: number;
  rawTierScore: number;
  synergyScore: number;
  counterPenalty: number;
  synergyPenalty: number;
  weights: RecommendationWeights;
  confidence: "高" | "中" | "低";
  evidence: MatchEvidence[];
  synergyEvidence: SynergyEvidence[];
  reasons: string[];
  risk: string;
};

const POSITION_HINT: Record<Position, string> = {
  对抗路: "抗压与边线",
  打野: "节奏与资源",
  中路: "支援与控制",
  发育路: "持续输出",
  游走: "视野与开团",
};

const createEmptyLineup = (): Lineup => ({
  对抗路: null,
  打野: null,
  中路: null,
  发育路: null,
  游走: null,
});

const clamp = (value: number, min = 0, max = 100) => Math.min(max, Math.max(min, value));

function hasPosition(hero: Hero, position: Position) {
  return hero.positions?.includes(position) || hero.roles.includes(position);
}

function tierFallback(tier?: string | null) {
  const map: Record<string, number> = { T0: 88, "T0.5": 76, T1: 64, T2: 48, T3: 32, T4: 18, T5: 10 };
  return map[tier || ""] ?? 42;
}

function HeroAvatar({ hero, size = "md" }: { hero: Hero; size?: "sm" | "md" | "lg" }) {
  return (
    <span className={`hero-avatar hero-avatar--${size}`} aria-hidden="true">
      <span>{hero.name.slice(0, 1)}</span>
      {/* Upstream provides the live hero portrait URL together with each record. */}
      <img
        src={hero.avatarUrl}
        alt=""
        loading="lazy"
        referrerPolicy="no-referrer"
        onError={(event) => {
          event.currentTarget.style.display = "none";
        }}
      />
    </span>
  );
}

function StatusPill({ meta, loading }: { meta: MetaResponse | null; loading: boolean }) {
  if (loading) {
    return <span className="data-pill data-pill--loading">正在同步峡谷数据</span>;
  }
  if (!meta) {
    return <span className="data-pill data-pill--error">数据暂不可用</span>;
  }
  const isFallback = meta.isFallback || meta.fallback?.used;
  return (
    <span className={`data-pill ${isFallback ? "data-pill--fallback" : ""}`}>
      <span className="live-dot" />
      {isFallback ? `${meta.queryDate} 巅峰千强 · 最近可用` : `${meta.queryDate} 巅峰千强`}
    </span>
  );
}

function LineupSlot({
  hero,
  position,
  side,
  isTarget,
  onPick,
  onClear,
}: {
  hero: Hero | null;
  position: Position;
  side: TeamSide;
  isTarget?: boolean;
  onPick: () => void;
  onClear: () => void;
}) {
  return (
    <div className={`lineup-slot ${hero ? "lineup-slot--filled" : ""} ${isTarget ? "lineup-slot--target" : ""}`}>
      <button
        type="button"
        className="lineup-slot__pick"
        onClick={onPick}
        aria-label={`${side === "ally" ? "我方" : "敌方"}${position}${hero ? `，当前 ${hero.name}` : "，选择英雄"}`}
      >
        {hero ? <HeroAvatar hero={hero} size="sm" /> : <span className="slot-plus">+</span>}
        <span className="slot-copy">
          <span className="slot-position">{position}</span>
          <span className="slot-name">{hero?.name || (side === "ally" ? "选择英雄" : "可选填")}</span>
        </span>
      </button>
      {hero && (
        <button type="button" className="slot-clear" onClick={onClear} aria-label={`移除 ${hero.name}`}>
          ×
        </button>
      )}
    </div>
  );
}

function HeroPicker({
  context,
  heroes,
  selectedNames,
  currentHero,
  onClose,
  onSelect,
  onClear,
}: {
  context: PickerContext;
  heroes: Hero[];
  selectedNames: Set<string>;
  currentHero: Hero | null;
  onClose: () => void;
  onSelect: (hero: Hero) => void;
  onClear?: () => void;
}) {
  const [query, setQuery] = useState("");
  const inputRef = useRef<HTMLInputElement>(null);
  const position = context.mode === "lineup" ? context.position : null;
  const pickerTitle = context.mode === "lineup"
    ? `选择${context.position}英雄`
    : context.mode === "ban"
      ? "选择已 Ban 英雄"
      : "查询哪位英雄";

  useEffect(() => {
    inputRef.current?.focus();
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", handleKey);
    return () => window.removeEventListener("keydown", handleKey);
  }, [onClose]);

  const filtered = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    return heroes
      .filter((hero) => !position || hasPosition(hero, position))
      .filter((hero) => !normalized || `${hero.name}${hero.roles}`.toLowerCase().includes(normalized))
      .sort((a, b) => (b.tierScore ?? tierFallback(b.tier)) - (a.tierScore ?? tierFallback(a.tier)));
  }, [heroes, position, query]);

  return (
    <div className="picker-backdrop" role="presentation" onMouseDown={onClose}>
      <section
        className="hero-picker"
        role="dialog"
        aria-modal="true"
        aria-labelledby="picker-title"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="picker-handle" />
        <header className="picker-header">
          <div>
            <p className="eyebrow">英雄池 · {heroes.length} 名</p>
            <h2 id="picker-title">{pickerTitle}</h2>
          </div>
          <button type="button" className="icon-button" onClick={onClose} aria-label="关闭英雄选择器">
            ×
          </button>
        </header>
        <label className="search-box">
          <span aria-hidden="true">⌕</span>
          <input
            ref={inputRef}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="搜索英雄，如：貂蝉"
            aria-label="搜索英雄"
          />
          {query && (
            <button type="button" onClick={() => setQuery("")} aria-label="清空搜索">
              清空
            </button>
          )}
        </label>
        <div className="picker-meta">
          <span>{position ? `已筛选 ${position}` : context.mode === "ban" ? "全部英雄 · 选中后加入 BAN 位" : "全部分路"}</span>
          <span>{filtered.length} 个结果</span>
        </div>
        <div className="hero-grid">
          {filtered.map((hero) => {
            const isCurrent = currentHero?.id === hero.id;
            const disabled = selectedNames.has(hero.name) && !isCurrent;
            return (
              <button
                type="button"
                className={`hero-option ${isCurrent ? "hero-option--current" : ""}`}
                key={hero.id}
                disabled={disabled}
                onClick={() => onSelect(hero)}
              >
                <HeroAvatar hero={hero} size="md" />
                <span className="hero-option__name">{hero.name}</span>
                <span className="hero-option__role">{hero.roles.replaceAll("(次分路)", "·次")}</span>
                {hero.tier && <span className={`mini-tier mini-tier--${hero.tier.replace(".", "-")}`}>{hero.tier}</span>}
                {disabled && <span className="hero-option__disabled">已选择</span>}
              </button>
            );
          })}
          {!filtered.length && (
            <div className="empty-search">
              <strong>没有找到“{query}”</strong>
              <span>试试英雄全名，或清空搜索查看该位置全部英雄。</span>
            </div>
          )}
        </div>
        {currentHero && onClear && (
          <button type="button" className="picker-clear" onClick={onClear}>
            清空当前槽位
          </button>
        )}
      </section>
    </div>
  );
}

function RelationList({
  title,
  description,
  relations,
  heroesByName,
  positive,
}: {
  title: string;
  description: string;
  relations: Relation[];
  heroesByName: Map<string, Hero>;
  positive: boolean;
}) {
  const visible = relations.slice(0, 12);
  return (
    <section className="relation-panel">
      <header>
        <div>
          <p className="eyebrow">近 30 天 · 显著关系</p>
          <h3>{title}</h3>
        </div>
        <span className={`relation-count ${positive ? "relation-count--positive" : "relation-count--danger"}`}>
          {relations.length}
        </span>
      </header>
      <p className="relation-description">{description}</p>
      <div className="relation-list">
        {visible.map((relation, index) => {
          const hero = heroesByName.get(relation.heroName);
          const value = Math.abs(relation.advantageIndex ?? 0);
          return (
            <div className="relation-row" key={`${relation.heroName}-${index}`}>
              <span className="relation-rank">{String(index + 1).padStart(2, "0")}</span>
              {hero ? <HeroAvatar hero={hero} size="sm" /> : <span className="avatar-fallback">{relation.heroName[0]}</span>}
              <span className="relation-hero">
                <strong>{relation.heroName}</strong>
                <small>{relation.totalMatches.toLocaleString("zh-CN")} 场样本</small>
              </span>
              <span className={positive ? "relation-value relation-value--positive" : "relation-value relation-value--danger"}>
                {positive ? "+" : "−"}{value.toFixed(2)}
                <small>优势指数</small>
              </span>
            </div>
          );
        })}
        {!visible.length && (
          <div className="relation-empty">当前样本中暂无达到显著阈值的英雄关系。</div>
        )}
      </div>
      {relations.length > visible.length && <p className="relation-more">优先展示关系最显著的前 {visible.length} 名</p>}
    </section>
  );
}

function RecommendationCard({
  recommendation,
  rank,
  target,
  onAdd,
  onBan,
}: {
  recommendation: Recommendation;
  rank: number;
  target: Position;
  onAdd: () => void;
  onBan: () => void;
}) {
  const { hero } = recommendation;
  const scoreStyle = { "--score": `${recommendation.score * 3.6}deg` } as CSSProperties;
  const enemyDisadvantages = recommendation.evidence.filter((item) => item.value < 0).length;
  const teammateConflicts = recommendation.synergyEvidence.filter((item) => item.value < 0).length;
  return (
    <article className={`recommend-card ${rank === 1 ? "recommend-card--first" : ""}`}>
      <div className="recommend-card__rank">{rank === 1 ? "首选" : `0${rank}`}</div>
      <div className="recommend-card__hero">
        <HeroAvatar hero={hero} size="lg" />
        <div>
          <div className="hero-title-line">
            <h3>{hero.name}</h3>
            <span className="tier-badge">{hero.tier || "—"}</span>
          </div>
          <p>{target} · {hero.roles.replaceAll("(次分路)", "次选")}</p>
        </div>
      </div>
      <div className="score-ring" style={scoreStyle} aria-label={`综合推荐分 ${recommendation.score}`}>
        <span>{recommendation.score}</span>
        <small>推荐分</small>
      </div>
      <div className="score-breakdown">
        <div className="metric-row metric-row--lift">
          <span>阵容提升</span>
          <span className="metric-track"><i style={{ width: `${recommendation.counterLiftScore}%` }} /></span>
          <strong>{recommendation.counterLiftScore}</strong>
        </div>
        <div className="metric-row">
          <span>对敌净分</span>
          <span className="metric-track"><i style={{ width: `${recommendation.matchupScore}%` }} /></span>
          <strong>{recommendation.matchupScore}</strong>
        </div>
        <div className="metric-row metric-row--synergy">
          <span>配合净分</span>
          <span className="metric-track"><i style={{ width: `${recommendation.synergyScore}%` }} /></span>
          <strong>{recommendation.synergyScore}</strong>
        </div>
        <div className="metric-row metric-row--tier">
          <span>压缩梯度</span>
          <span className="metric-track"><i style={{ width: `${recommendation.tierScore}%` }} /></span>
          <strong>{recommendation.tierScore}</strong>
        </div>
        <div className="penalty-strip" aria-label="负向关系扣分">
          <span className={recommendation.counterPenalty ? "is-negative" : ""}>敌方劣势 {enemyDisadvantages} 项 · −{recommendation.counterPenalty}</span>
          <span className={recommendation.synergyPenalty ? "is-negative" : ""}>队友冲突 {teammateConflicts} 项 · −{recommendation.synergyPenalty}</span>
        </div>
      </div>
      <ul className="reason-list">
        {recommendation.reasons.slice(0, 3).map((reason) => <li key={reason}>{reason}</li>)}
      </ul>
      <div className="card-footer">
        <details>
          <summary>查看依据</summary>
          <div className="evidence-popover">
            <div>
              <span>本次权重</span>
              <strong>
                提升 {Math.round(recommendation.weights.counterLift * 100)}% · 对敌 {Math.round(recommendation.weights.matchup * 100)}% · 配合 {Math.round(recommendation.weights.synergy * 100)}% · 梯度 {Math.round(recommendation.weights.tier * 100)}%
              </strong>
            </div>
            <div><span>可信度</span><strong>{recommendation.confidence}</strong></div>
            <div><span>阵容提升</span><strong>对敌 {recommendation.matchupScore} 相对梯度 {recommendation.tierScore} → {recommendation.counterLiftScore}</strong></div>
            <div><span>原始梯度</span><strong>{recommendation.rawTierScore} → 压缩为 {recommendation.tierScore}</strong></div>
            <div><span>敌方负向</span><strong>{enemyDisadvantages} 项 · 扣 {recommendation.counterPenalty} 分</strong></div>
            <div><span>队友负向</span><strong>{teammateConflicts} 项 · 扣 {recommendation.synergyPenalty} 分</strong></div>
            <p>{recommendation.risk}</p>
          </div>
        </details>
        <div className="card-footer-actions">
          <button type="button" className="card-ban-button" onClick={onBan}>设为已 Ban</button>
          <button type="button" onClick={onAdd}>加入{target}</button>
        </div>
      </div>
    </article>
  );
}

export function BpAssistant() {
  const [view, setView] = useState<MainView>("recommend");
  const [heroes, setHeroes] = useState<Hero[]>([]);
  const [meta, setMeta] = useState<MetaResponse | null>(null);
  const [metaLoading, setMetaLoading] = useState(true);
  const [metaError, setMetaError] = useState("");
  const [ally, setAlly] = useState<Lineup>(createEmptyLineup);
  const [enemy, setEnemy] = useState<Lineup>(createEmptyLineup);
  const [bannedHeroes, setBannedHeroes] = useState<Hero[]>([]);
  const [target, setTarget] = useState<Position>("中路");
  const [picker, setPicker] = useState<PickerContext | null>(null);
  const [counterHero, setCounterHero] = useState<Hero | null>(null);
  const [analyses, setAnalyses] = useState<Record<number, Analysis>>({});
  const [analysisPending, setAnalysisPending] = useState<Set<number>>(new Set());
  const [analysisError, setAnalysisError] = useState("");
  const pendingAnalysisRef = useRef<Set<number>>(new Set());

  const loadMeta = useCallback(async (reset = false) => {
    setMetaLoading(true);
    setMetaError("");
    try {
      const response = await fetch(`/api/meta${reset ? `?refresh=${Date.now()}` : ""}`, { cache: "no-store" });
      const data = (await response.json()) as MetaResponse & { error?: string };
      if (!response.ok) throw new Error(data.error || "实时数据同步失败");
      setHeroes(data.heroes || []);
      setMeta(data);
      if (reset) setAnalyses({});
    } catch (error) {
      setMetaError(error instanceof Error ? error.message : "实时数据同步失败");
    } finally {
      setMetaLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadMeta();
  }, [loadMeta]);

  const selectedNames = useMemo(() => {
    const names = new Set<string>();
    [...Object.values(ally), ...Object.values(enemy), ...bannedHeroes].forEach((hero) => hero && names.add(hero.name));
    return names;
  }, [ally, enemy, bannedHeroes]);

  const selectedIds = useMemo(() => {
    const ids = new Set<number>();
    [...Object.values(ally), ...Object.values(enemy)].forEach((hero) => hero && ids.add(hero.id));
    if (counterHero) ids.add(counterHero.id);
    return [...ids];
  }, [ally, enemy, counterHero]);

  useEffect(() => {
    const missing = selectedIds.filter((id) => !analyses[id] && !pendingAnalysisRef.current.has(id));
    if (!missing.length) return;
    missing.forEach((id) => pendingAnalysisRef.current.add(id));
    setAnalysisPending((previous) => new Set([...previous, ...missing]));
    setAnalysisError("");
    Promise.all(
      missing.map(async (id) => {
        const response = await fetch(`/api/analysis?heroId=${id}`, { cache: "no-store" });
        const payload = (await response.json()) as Analysis & { error?: string };
        if (!response.ok) throw new Error(payload.error || "克制关系获取失败");
        return payload;
      }),
    )
      .then((results) => {
        setAnalyses((previous) => {
          const next = { ...previous };
          results.forEach((result) => { next[result.heroId] = result; });
          return next;
        });
      })
      .catch((error) => {
        setAnalysisError(error instanceof Error ? error.message : "克制关系获取失败");
      })
      .finally(() => {
        missing.forEach((id) => pendingAnalysisRef.current.delete(id));
        setAnalysisPending((previous) => {
          const next = new Set(previous);
          missing.forEach((id) => next.delete(id));
          return next;
        });
      });
  }, [selectedIds, analyses]);

  const heroesByName = useMemo(() => new Map(heroes.map((hero) => [hero.name, hero])), [heroes]);

  const recommendations = useMemo<Recommendation[]>(() => {
    const excluded = selectedNames;
    const enemyEntries = POSITIONS.map((position) => ({ position, hero: enemy[position] })).filter(
      (entry): entry is { position: Position; hero: Hero } => Boolean(entry.hero),
    );
    const allyHeroes = POSITIONS
      .filter((position) => position !== target)
      .map((position) => ally[position])
      .filter((hero): hero is Hero => Boolean(hero));

    return heroes
      .filter((hero) => hasPosition(hero, target) && !excluded.has(hero.name))
      .map((hero): Recommendation => {
        const evidence: MatchEvidence[] = [];
        const matchupRelations: Array<{ value: number; weight: number }> = [];

        enemyEntries.forEach(({ position, hero: opposingHero }) => {
          const analysis = analyses[opposingHero.id];
          if (!analysis) return;
          const sameLane = position === target;
          const weight = sameLane ? 1.15 : 0.9;
          const favorable = analysis.counteredBy.find((item) => item.heroName === hero.name);
          const unfavorable = analysis.counters.find((item) => item.heroName === hero.name);
          const relation = favorable || unfavorable;
          if (!relation) {
            matchupRelations.push({ value: 0, weight });
            return;
          }
          const value = favorable
            ? Math.abs(favorable.advantageIndex ?? 0)
            : -Math.abs(unfavorable?.advantageIndex ?? 0);
          matchupRelations.push({ value, weight });
          evidence.push({ enemy: opposingHero.name, value, matches: relation.totalMatches, sameLane });
        });

        const rawTierScore = Math.round(clamp(hero.tierScore ?? tierFallback(hero.tier)));
        const heroTierScore = compressTierScore(rawTierScore);

        const synergyEvidence: SynergyEvidence[] = [];
        const synergyRelations: Array<{ value: number }> = [];
        for (const allyHero of allyHeroes) {
          const analysis = analyses[allyHero.id];
          if (!analysis) continue;
          const good = analysis.goodSynergies.find((item) => item.heroName === hero.name);
          const bad = analysis.badSynergies.find((item) => item.heroName === hero.name);
          if (good) {
            const value = Math.abs(good.synergyIndex ?? 0);
            synergyRelations.push({ value });
            synergyEvidence.push({ ally: allyHero.name, value, matches: good.totalMatches });
          } else if (bad) {
            const value = -Math.abs(bad.synergyIndex ?? 0);
            synergyRelations.push({ value });
            synergyEvidence.push({ ally: allyHero.name, value, matches: bad.totalMatches });
          } else {
            synergyRelations.push({ value: 0 });
          }
        }
        const matchupBreakdown = calculateRelationshipBreakdown(matchupRelations, 4);
        const synergyBreakdown = calculateRelationshipBreakdown(synergyRelations, 4.2);
        const matchupScore = Math.round(clamp(matchupBreakdown.score, 8, 94));
        const synergyScore = Math.round(clamp(synergyBreakdown.score, 12, 92));
        const counterLiftScore = calculateCounterLiftScore(matchupScore, heroTierScore);
        const { score, weights } = calculateRecommendationScore({
          matchupScore,
          counterLiftScore,
          synergyScore,
          tierScore: heroTierScore,
          enemyCount: enemyEntries.length,
          allyCount: allyHeroes.length,
        });
        const sampleTotal = evidence.reduce((sum, item) => sum + item.matches, 0)
          + synergyEvidence.reduce((sum, item) => sum + item.matches, 0);
        const confidence: Recommendation["confidence"] = sampleTotal >= 800
          ? "高"
          : sampleTotal >= 180 || evidence.length >= 2
            ? "中"
            : "低";

        const favorableEvidence = evidence.filter((item) => item.value > 0);
        const unfavorableEvidence = evidence.filter((item) => item.value < 0);
        const positiveSynergies = synergyEvidence.filter((item) => item.value > 0);
        const negativeSynergies = synergyEvidence.filter((item) => item.value < 0);
        const worstMatchup = [...unfavorableEvidence].sort((a, b) => a.value - b.value)[0];
        const worstSynergy = [...negativeSynergies].sort((a, b) => a.value - b.value)[0];
        const reasons: string[] = [];
        if (matchupRelations.length) {
          reasons.push(`敌方：有利 ${favorableEvidence.length}/${matchupRelations.length}，劣势 ${unfavorableEvidence.length}/${matchupRelations.length}${worstMatchup ? `；最差对 ${worstMatchup.enemy} ${worstMatchup.value.toFixed(2)}` : ""}`);
        } else {
          reasons.push(enemyEntries.length ? "敌方关系正在同步，当前按中性值计算" : "尚未录入敌方英雄，克制项不计权重");
        }
        if (synergyRelations.length) {
          reasons.push(`队友：配合 ${positiveSynergies.length}/${synergyRelations.length}，冲突 ${negativeSynergies.length}/${synergyRelations.length}${worstSynergy ? `；最差与 ${worstSynergy.ally} ${worstSynergy.value.toFixed(2)}` : ""}`);
        } else {
          reasons.push(allyHeroes.length ? "队友关系正在同步，当前按中性值计算" : "尚未录入队友，配合项不计权重");
        }
        reasons.push(`阵容提升 ${counterLiftScore}：对敌 ${matchupScore} 相对梯度基准 ${heroTierScore}`);

        const risk = matchupScore < 44
          ? "风险：对位数据偏弱，建议结合熟练度谨慎选择。"
          : synergyScore < 44
            ? "风险：与现有队友的组合样本偏弱，建议优先确认阵容联动。"
          : confidence === "低"
            ? "提示：显著关系样本较少，关系项暂按中性值计算。"
            : "提示：推荐分代表当前数据适配度，不等同于对局胜率。";

        return {
          hero,
          score,
          counterLiftScore,
          matchupScore,
          tierScore: heroTierScore,
          rawTierScore,
          synergyScore,
          counterPenalty: matchupBreakdown.negativePenalty,
          synergyPenalty: synergyBreakdown.negativePenalty,
          weights,
          confidence,
          evidence,
          synergyEvidence,
          reasons,
          risk,
        };
      })
      .sort((a, b) => b.score - a.score
        || b.counterLiftScore - a.counterLiftScore
        || (b.matchupScore + b.synergyScore) - (a.matchupScore + a.synergyScore)
        || b.tierScore - a.tierScore)
      .slice(0, 5);
  }, [heroes, target, selectedNames, ally, enemy, analyses]);

  const pickerCurrentHero = useMemo(() => {
    if (!picker || picker.mode === "ban") return null;
    if (picker.mode === "counter") return counterHero;
    return (picker.side === "ally" ? ally : enemy)[picker.position];
  }, [picker, counterHero, ally, enemy]);

  const chooseHero = (hero: Hero) => {
    if (!picker) return;
    if (picker.mode === "counter") {
      setCounterHero(hero);
      setView("counter");
    } else if (picker.mode === "ban") {
      setBannedHeroes((previous) => previous.some((item) => item.id === hero.id) ? previous : [...previous, hero]);
    } else {
      const setter = picker.side === "ally" ? setAlly : setEnemy;
      setter((previous) => ({ ...previous, [picker.position]: hero }));
    }
    setPicker(null);
  };

  const clearPickerSlot = () => {
    if (!picker || picker.mode !== "lineup") return;
    const setter = picker.side === "ally" ? setAlly : setEnemy;
    setter((previous) => ({ ...previous, [picker.position]: null }));
    setPicker(null);
  };

  const fillExample = () => {
    const find = (name: string) => heroes.find((hero) => hero.name === name) || null;
    setAlly({ 对抗路: find("廉颇"), 打野: find("镜"), 中路: null, 发育路: find("公孙离"), 游走: find("张飞") });
    setEnemy({ 对抗路: find("吕布"), 打野: find("兰陵王"), 中路: find("不知火舞"), 发育路: find("后羿"), 游走: find("朵莉亚") });
    setBannedHeroes([]);
    setTarget("中路");
  };

  const clearLineups = () => {
    setAlly(createEmptyLineup());
    setEnemy(createEmptyLineup());
    setBannedHeroes([]);
  };

  const counterAnalysis = counterHero ? analyses[counterHero.id] : null;
  const counterLoading = Boolean(counterHero && analysisPending.has(counterHero.id));
  const recommendLoading = selectedIds.some((id) => analysisPending.has(id));

  return (
    <main className="site-shell">
      <div className="ambient ambient--one" />
      <div className="ambient ambient--two" />
      <header className="topbar">
        <a className="brand" href="#top" aria-label="峡谷 BP 首页">
          <span className="brand-mark">峡</span>
          <span><strong>峡谷 BP</strong><small>REALTIME DRAFT LAB</small></span>
        </a>
        <nav className="main-nav" aria-label="主要功能">
          <button className={view === "recommend" ? "is-active" : ""} onClick={() => setView("recommend")}>阵容推荐</button>
          <button className={view === "counter" ? "is-active" : ""} onClick={() => setView("counter")}>克制查询</button>
        </nav>
        <div className="topbar-actions">
          <StatusPill meta={meta} loading={metaLoading} />
          <button type="button" className="refresh-button" onClick={() => void loadMeta(true)} disabled={metaLoading}>
            <span aria-hidden="true">↻</span><span>刷新</span>
          </button>
        </div>
      </header>

      <section className="hero-intro" id="top">
        <div>
          <p className="overline"><span>LIVE META</span> 先看对位，再看梯度</p>
          <h1>别凭感觉补位，<br /><em>让数据替你锁定答案。</em></h1>
          <p className="intro-copy">录入双方阵容、BAN 位与待补位置，系统会把巅峰千强近 30 天的敌方克制、友方配合和当日英雄梯度合成一份可解释的选将建议。</p>
        </div>
        <div className="intro-stats" aria-label="数据能力">
          <div><strong>{heroes.length || "—"}</strong><span>英雄实时覆盖</span></div>
          <div><strong>50<small>%</small></strong><span>对敌克制权重</span></div>
          <div><strong>30<small>%</small></strong><span>队友配合权重</span></div>
          <div><strong>20<small>%</small></strong><span>压缩梯度权重</span></div>
        </div>
      </section>

      {metaError && (
        <div className="global-alert" role="alert">
          <span>!</span><div><strong>暂时没能同步实时数据</strong><p>{metaError}</p></div>
          <button type="button" onClick={() => void loadMeta(true)}>重新同步</button>
        </div>
      )}

      {view === "recommend" ? (
        <section className="workspace workspace--recommend">
          <aside className="draft-panel">
            <header className="section-heading">
              <div><p className="eyebrow">DRAFT INPUT</p><h2>录入当前阵容</h2></div>
              <div className="section-actions">
                <button type="button" onClick={fillExample} disabled={!heroes.length}>填入示例</button>
                <button type="button" onClick={clearLineups}>清空</button>
              </div>
            </header>

            <div className="team-block team-block--ally">
              <div className="team-label"><span className="team-dot" /><strong>我方已选</strong><small>点击槽位选择英雄</small></div>
              <div className="lineup-list">
                {POSITIONS.map((position) => (
                  <LineupSlot
                    key={`ally-${position}`}
                    hero={ally[position]}
                    position={position}
                    side="ally"
                    isTarget={position === target}
                    onPick={() => setPicker({ mode: "lineup", side: "ally", position })}
                    onClear={() => setAlly((previous) => ({ ...previous, [position]: null }))}
                  />
                ))}
              </div>
            </div>

            <div className="team-divider"><span>VS</span></div>

            <div className="team-block team-block--enemy">
              <div className="team-label"><span className="team-dot" /><strong>敌方已选</strong><small>敌方全阵容均参与</small></div>
              <div className="lineup-list">
                {POSITIONS.map((position) => (
                  <LineupSlot
                    key={`enemy-${position}`}
                    hero={enemy[position]}
                    position={position}
                    side="enemy"
                    onPick={() => setPicker({ mode: "lineup", side: "enemy", position })}
                    onClear={() => setEnemy((previous) => ({ ...previous, [position]: null }))}
                  />
                ))}
              </div>
            </div>

            <div className="ban-block">
              <div className="ban-heading">
                <div><span className="ban-mark">×</span><strong>BAN 位</strong><small>{bannedHeroes.length ? `已禁用 ${bannedHeroes.length} 名` : "可选填"}</small></div>
                <button type="button" onClick={() => setPicker({ mode: "ban" })}>＋ 添加英雄</button>
              </div>
              <div className={`ban-list ${bannedHeroes.length ? "" : "ban-list--empty"}`}>
                {bannedHeroes.map((hero) => (
                  <div className="ban-chip" key={`ban-${hero.id}`}>
                    <HeroAvatar hero={hero} size="sm" />
                    <span>{hero.name}</span>
                    <button
                      type="button"
                      onClick={() => setBannedHeroes((previous) => previous.filter((item) => item.id !== hero.id))}
                      aria-label={`取消 Ban ${hero.name}`}
                    >×</button>
                  </div>
                ))}
                {!bannedHeroes.length && (
                  <button type="button" className="ban-empty-action" onClick={() => setPicker({ mode: "ban" })}>
                    <span>＋</span>选择已被 Ban 的英雄，推荐会自动跳过
                  </button>
                )}
              </div>
            </div>

            <div className="position-picker">
              <div className="position-picker__label"><span>待补位置</span><small>{POSITION_HINT[target]}</small></div>
              <div className="position-chips">
                {POSITIONS.map((position) => (
                  <button
                    type="button"
                    key={position}
                    className={target === position ? "is-active" : ""}
                    onClick={() => setTarget(position)}
                  >
                    {position}
                  </button>
                ))}
              </div>
            </div>
          </aside>

          <section className="result-panel">
            <header className="result-heading">
              <div>
                <p className="eyebrow">SMART PICKS · {target}</p>
                <h2>{ally[target] ? `替换 ${ally[target]?.name} 的候选` : `${target}补位推荐`}</h2>
                <p>{enemy[target] ? `重点计算与 ${enemy[target]?.name} 的同路对位，并覆盖敌方其余阵容` : `综合敌方全阵容克制与我方配合${Object.values(enemy).some(Boolean) ? "" : "，补充敌方后会更准确"}`}{bannedHeroes.length ? ` · 已排除 ${bannedHeroes.length} 名被 Ban 英雄` : ""}</p>
              </div>
              <div className="method-tag"><span>四因子</span><strong>提升 × 克制 × 配合 × 梯度</strong></div>
            </header>

            {(metaLoading || (recommendLoading && !recommendations.length)) && (
              <div className="recommend-skeleton" aria-label="正在计算推荐">
                {[1, 2, 3].map((item) => <div key={item}><i /><span /><span /><b /></div>)}
              </div>
            )}

            {!metaLoading && recommendations.length > 0 && (
              <div className={`recommend-list ${recommendLoading ? "recommend-list--updating" : ""}`}>
                {recommendLoading && <div className="updating-label"><span />正在重新计算关系</div>}
                {recommendations.map((recommendation, index) => (
                  <RecommendationCard
                    key={`${target}-${recommendation.hero.id}`}
                    recommendation={recommendation}
                    rank={index + 1}
                    target={target}
                    onAdd={() => setAlly((previous) => ({ ...previous, [target]: recommendation.hero }))}
                    onBan={() => setBannedHeroes((previous) => previous.some((item) => item.id === recommendation.hero.id) ? previous : [...previous, recommendation.hero])}
                  />
                ))}
              </div>
            )}

            {!metaLoading && !recommendations.length && !metaError && (
              <div className="result-empty"><span>◇</span><h3>暂无可用候选</h3><p>该位置的英雄可能已经出现在双方阵容或 BAN 位中，请移除部分选择后重试。</p></div>
            )}

            {analysisError && <p className="inline-warning">部分关系暂未同步，缺失项会自动按中性值处理并降低影响：{analysisError}</p>}
            <footer className="result-note">
              <span>i</span>
              <p>双方阵容均已录入时：阵容提升 30% + 对敌净分 25% + 队友配合 30% + 压缩梯度 15%；正负关系完全来自巅峰千强样本，劣势与冲突单独扣分。</p>
            </footer>
          </section>
        </section>
      ) : (
        <section className="workspace workspace--counter">
          <div className="counter-search-panel">
            <div>
              <p className="eyebrow">COUNTER SCOUT</p>
              <h2>谁能压住 TA？</h2>
              <p>选择一名英雄，同时查看“克制 TA”和“TA 克制”的显著关系与真实样本量。</p>
            </div>
            <button type="button" className="counter-hero-input" onClick={() => setPicker({ mode: "counter" })}>
              {counterHero ? <HeroAvatar hero={counterHero} size="lg" /> : <span className="counter-search-icon">⌕</span>}
              <span><small>目标英雄</small><strong>{counterHero?.name || "搜索英雄名称"}</strong></span>
              <i>选择</i>
            </button>
            {!counterHero && (
              <div className="quick-picks">
                <span>热门查询</span>
                {["貂蝉", "兰陵王", "后羿", "大乔"].map((name) => {
                  const hero = heroesByName.get(name);
                  return hero ? <button type="button" key={name} onClick={() => setCounterHero(hero)}>{name}</button> : null;
                })}
              </div>
            )}
          </div>

          {counterHero && (
            <div className="counter-results">
              <div className="counter-profile">
                <HeroAvatar hero={counterHero} size="lg" />
                <div><p className="eyebrow">当前目标</p><h2>{counterHero.name}</h2><span>{counterHero.roles}</span></div>
                <div className="counter-profile__tier"><small>今日梯度</small><strong>{counterHero.tier || "—"}</strong><span>{Math.round(counterHero.tierScore ?? tierFallback(counterHero.tier))} 分</span></div>
              </div>
              {counterLoading && (
                <div className="counter-loading"><span /><p>正在抓取 {counterHero.name} 的最新克制样本…</p></div>
              )}
              {!counterLoading && counterAnalysis && (
                <div className="relation-grid">
                  <RelationList
                    title={`能克制 ${counterHero.name}`}
                    description="这些英雄在样本中让目标英雄处于显著劣势，优先关注同位置选择。"
                    relations={counterAnalysis.counteredBy}
                    heroesByName={heroesByName}
                    positive
                  />
                  <RelationList
                    title={`${counterHero.name} 克制谁`}
                    description="这些英雄面对目标英雄时更容易处于劣势，选用前需准备替代方案。"
                    relations={counterAnalysis.counters}
                    heroesByName={heroesByName}
                    positive={false}
                  />
                </div>
              )}
              {!counterLoading && !counterAnalysis && analysisError && (
                <div className="result-empty"><span>!</span><h3>关系数据暂不可用</h3><p>{analysisError}</p></div>
              )}
            </div>
          )}
        </section>
      )}

      <footer className="site-footer">
        <div><strong>峡谷 BP</strong><span>让每一次锁定都有依据。</span></div>
        <p>
          数据来源：<a href="https://tianyuanzhiyi.com/" target="_blank" rel="noreferrer">天元之弈数据站 · 巅峰千强</a>
          {meta && <> · 抓取于 {new Date(meta.fetchedAt).toLocaleString("zh-CN", { hour12: false })}</>}
        </p>
        <small>第三方数据分析工具，与腾讯游戏 / 天美工作室无关联。数据仅供个人学习与 BP 参考。</small>
      </footer>

      {picker && (
        <HeroPicker
          context={picker}
          heroes={heroes}
          selectedNames={picker.mode === "counter" ? new Set() : selectedNames}
          currentHero={pickerCurrentHero}
          onClose={() => setPicker(null)}
          onSelect={chooseHero}
          onClear={picker.mode === "lineup" ? clearPickerSlot : undefined}
        />
      )}
    </main>
  );
}
