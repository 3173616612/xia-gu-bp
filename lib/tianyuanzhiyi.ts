const API_BASE_URL = "https://tianyuanzhiyi.com";
const CHINA_TIME_ZONE = "Asia/Shanghai";
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

export const TIANYUAN_SOURCE = {
  name: "天元之弈",
  url: API_BASE_URL,
  attribution: "数据来源：天元之弈（公开对局数据，仅供参考）",
} as const;

// The upstream hero and tier pages currently identify these endpoints as
// 巅峰千强 data. Neither endpoint exposes a mode selector, so this proxy keeps
// that upstream mode explicit instead of implying a configurable data scope.
export const TIANYUAN_MODE = "巅峰千强" as const;

type JsonRecord = Record<string, unknown>;

export interface HeroTier {
  heroId: number;
  heroName: string;
  avatarUrl: string | null;
  date: string | null;
  role: string;
  trueHeroPowerInRole: number | null;
  finalNormalizedTierScore: number | null;
  tierInRole: string | null;
  rankInRole: number | null;
  lowPick: boolean;
  highBan: boolean;
}

export interface HeroMeta {
  id: number;
  name: string;
  avatarUrl: string | null;
  roles: string;
  positions: string[];
  roleList: string[];
  tier: string | null;
  tierScore: number | null;
  tierLabel: string | null;
  tierRole: string | null;
  rankInRole: number | null;
  tierRank: number | null;
  highBan: boolean;
  lowPick: boolean;
  tierDetails: HeroTier | null;
}

export interface FallbackStatus {
  used: boolean;
  days: number;
  attemptedDates: string[];
}

export interface MetaPayload {
  source: string;
  sourceUrl: string;
  sourceMeta: typeof TIANYUAN_SOURCE;
  mode: typeof TIANYUAN_MODE;
  fetchedAt: string;
  requestedDate: string;
  queryDate: string;
  stale: boolean;
  isFallback: boolean;
  fallbackDays: number;
  fallback: FallbackStatus;
  heroes: HeroMeta[];
  tiers: HeroTier[];
}

export interface CounterRelation {
  heroName: string;
  totalMatches: number;
  advantageIndex: number;
}

export interface SynergyRelation {
  heroName: string;
  totalMatches: number;
  synergyIndex: number;
}

export interface AnalysisPayload {
  source: string;
  sourceUrl: string;
  sourceMeta: typeof TIANYUAN_SOURCE;
  mode: typeof TIANYUAN_MODE;
  fetchedAt: string;
  queryDate: null;
  stale: false;
  fallback: {
    used: false;
    days: 0;
    attemptedDates: [];
  };
  dataWindow: string;
  heroId: number;
  counters: CounterRelation[];
  counteredBy: CounterRelation[];
  goodSynergies: SynergyRelation[];
  badSynergies: SynergyRelation[];
}

export class TianyuanzhiyiError extends Error {
  readonly httpStatus: number;
  readonly upstreamStatus: number | null;
  readonly endpoint: string | null;

  constructor(
    message: string,
    options: {
      httpStatus?: number;
      upstreamStatus?: number | null;
      endpoint?: string | null;
    } = {},
  ) {
    super(message);
    this.name = "TianyuanzhiyiError";
    this.httpStatus = options.httpStatus ?? 502;
    this.upstreamStatus = options.upstreamStatus ?? null;
    this.endpoint = options.endpoint ?? null;
  }
}

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function cleanString(value: unknown, maxLength = 120): string | null {
  if (typeof value !== "string") return null;
  const result = value.trim();
  if (!result) return null;
  return result.slice(0, maxLength);
}

function cleanNumber(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function cleanPositiveInteger(value: unknown): number | null {
  const number = cleanNumber(value);
  return number !== null && Number.isInteger(number) && number > 0 ? number : null;
}

function cleanNonNegativeInteger(value: unknown): number | null {
  const number = cleanNumber(value);
  return number !== null && number >= 0 ? Math.floor(number) : null;
}

function cleanHttpUrl(value: unknown): string | null {
  const candidate = cleanString(value, 600);
  if (!candidate) return null;

  try {
    const url = new URL(candidate);
    return url.protocol === "https:" || url.protocol === "http:" ? url.toString() : null;
  } catch {
    return null;
  }
}

function payloadMessage(payload: unknown): string | null {
  if (!isRecord(payload)) return null;
  return cleanString(payload.error, 240) ?? cleanString(payload.message, 240);
}

async function fetchUpstreamJson(pathAndSearch: string): Promise<unknown> {
  const url = new URL(pathAndSearch, API_BASE_URL);
  let response: Response;

  try {
    response = await fetch(url, {
      cache: "no-store",
      headers: {
        Accept: "application/json",
      },
    });
  } catch {
    throw new TianyuanzhiyiError("暂时无法连接天元之弈数据源，请稍后重试。", {
      endpoint: url.pathname,
    });
  }

  let payload: unknown = null;
  try {
    const body = await response.text();
    payload = body ? JSON.parse(body) : null;
  } catch {
    if (response.ok) {
      throw new TianyuanzhiyiError("天元之弈返回了无法解析的数据。", {
        endpoint: url.pathname,
        upstreamStatus: response.status,
      });
    }
  }

  if (!response.ok) {
    const message = payloadMessage(payload);
    const notFound = response.status === 404;
    throw new TianyuanzhiyiError(
      notFound
        ? message ?? "天元之弈暂无对应数据。"
        : message
          ? `天元之弈数据暂不可用：${message}`
          : `天元之弈数据请求失败（状态码 ${response.status}）。`,
      {
        endpoint: url.pathname,
        upstreamStatus: response.status,
        httpStatus: notFound ? 404 : 502,
      },
    );
  }

  return payload;
}

function chinaDateToday(now = new Date()): string {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: CHINA_TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(now);

  const values: Record<string, string> = {};
  for (const part of parts) values[part.type] = part.value;
  return `${values.year}-${values.month}-${values.day}`;
}

function shiftDate(date: string, days: number): string {
  const [year, month, day] = date.split("-").map(Number);
  const shifted = new Date(Date.UTC(year, month - 1, day + days));
  return shifted.toISOString().slice(0, 10);
}

function dateDistanceInDays(laterDate: string, earlierDate: string): number {
  const later = Date.parse(`${laterDate}T00:00:00Z`);
  const earlier = Date.parse(`${earlierDate}T00:00:00Z`);
  if (!Number.isFinite(later) || !Number.isFinite(earlier)) return 0;
  return Math.max(0, Math.round((later - earlier) / 86_400_000));
}

function cleanHeroList(payload: unknown) {
  if (!Array.isArray(payload)) {
    throw new TianyuanzhiyiError("天元之弈英雄列表格式异常。", {
      endpoint: "/api/allheroes",
    });
  }

  const heroes = payload.flatMap((value) => {
    if (!isRecord(value)) return [];
    const id = cleanPositiveInteger(value.id);
    const name = cleanString(value.name, 60);
    const roles = cleanString(value.roles, 80);
    if (id === null || !name || !roles) return [];

    return [
      {
        id,
        name,
        avatarUrl: cleanHttpUrl(value.avatarUrl),
        roles,
        roleList: Array.from(
          new Set(
            roles
              .split("/")
              .map((role) => role.trim())
              .filter(Boolean),
          ),
        ),
      },
    ];
  });

  if (heroes.length === 0) {
    throw new TianyuanzhiyiError("天元之弈暂未返回可用英雄。", {
      endpoint: "/api/allheroes",
    });
  }

  return heroes;
}

function cleanTier(value: unknown): HeroTier | null {
  if (!isRecord(value)) return null;
  const heroId = cleanPositiveInteger(value.heroId);
  const heroName = cleanString(value.heroName, 60);
  const role = cleanString(value.role, 100);
  if (heroId === null || !heroName || !role) return null;

  const rawDate = cleanString(value.date, 10);
  return {
    heroId,
    heroName,
    avatarUrl: cleanHttpUrl(value.avatarUrl),
    date: rawDate && DATE_PATTERN.test(rawDate) ? rawDate : null,
    role,
    trueHeroPowerInRole: cleanNumber(value.trueHeroPowerInRole),
    finalNormalizedTierScore: cleanNumber(value.finalNormalizedTierScore),
    tierInRole: cleanString(value.tierInRole, 16),
    rankInRole: cleanPositiveInteger(value.rankInRole),
    lowPick: value.lowPick === true,
    highBan: value.highBan === true,
  };
}

async function fetchAllHeroes() {
  return cleanHeroList(await fetchUpstreamJson("/api/allheroes"));
}

async function fetchTierWithFallback(requestedDate: string) {
  const dates = [requestedDate, shiftDate(requestedDate, -1), shiftDate(requestedDate, -2)];
  const attemptedDates: string[] = [];
  let lastError: unknown = null;

  for (let index = 0; index < dates.length; index += 1) {
    const candidateDate = dates[index];
    attemptedDates.push(candidateDate);

    try {
      const payload = await fetchUpstreamJson(
        `/api/global/tier?date=${encodeURIComponent(candidateDate)}`,
      );
      if (!isRecord(payload) || !Array.isArray(payload.tiers)) {
        throw new TianyuanzhiyiError("天元之弈梯度数据格式异常。", {
          endpoint: "/api/global/tier",
        });
      }

      const tiers = payload.tiers.map(cleanTier).filter((tier): tier is HeroTier => tier !== null);
      if (tiers.length === 0) {
        throw new TianyuanzhiyiError(
          payloadMessage(payload) ?? `${candidateDate} 暂无可用英雄梯度数据。`,
          { endpoint: "/api/global/tier" },
        );
      }

      const upstreamDate = cleanString(payload.queryDate, 10);
      const queryDate = upstreamDate && DATE_PATTERN.test(upstreamDate) ? upstreamDate : candidateDate;
      const days = Math.max(index, dateDistanceInDays(requestedDate, queryDate));

      return {
        tiers,
        queryDate,
        fallback: {
          used: days > 0,
          days,
          attemptedDates,
        } satisfies FallbackStatus,
      };
    } catch (error) {
      lastError = error;
    }
  }

  const detail =
    lastError instanceof TianyuanzhiyiError
      ? lastError.message
      : "连续三个日期均未返回可用数据。";
  throw new TianyuanzhiyiError(
    `天元之弈近三日梯度数据均不可用：${detail}`,
    { endpoint: "/api/global/tier" },
  );
}

export async function fetchMeta(): Promise<MetaPayload> {
  const requestedDate = chinaDateToday();
  const [heroes, tierResult] = await Promise.all([
    fetchAllHeroes(),
    fetchTierWithFallback(requestedDate),
  ]);
  const tierByHeroId = new Map(tierResult.tiers.map((tier) => [tier.heroId, tier]));

  const mergedHeroes: HeroMeta[] = heroes.map((hero) => {
    const tierDetails = tierByHeroId.get(hero.id) ?? null;
    return {
      ...hero,
      positions: hero.roleList,
      tier: tierDetails?.tierInRole ?? null,
      tierScore: tierDetails?.finalNormalizedTierScore ?? null,
      tierLabel: tierDetails?.tierInRole ?? null,
      tierRole: tierDetails?.role ?? null,
      rankInRole: tierDetails?.rankInRole ?? null,
      tierRank: tierDetails?.rankInRole ?? null,
      highBan: tierDetails?.highBan ?? false,
      lowPick: tierDetails?.lowPick ?? false,
      tierDetails,
    };
  });

  return {
    source: TIANYUAN_SOURCE.name,
    sourceUrl: TIANYUAN_SOURCE.url,
    sourceMeta: TIANYUAN_SOURCE,
    mode: TIANYUAN_MODE,
    fetchedAt: new Date().toISOString(),
    requestedDate,
    queryDate: tierResult.queryDate,
    stale: tierResult.fallback.used,
    isFallback: tierResult.fallback.used,
    fallbackDays: tierResult.fallback.days,
    fallback: tierResult.fallback,
    heroes: mergedHeroes,
    tiers: tierResult.tiers,
  };
}

function cleanCounters(value: unknown): CounterRelation[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (!isRecord(item)) return [];
    const heroName = cleanString(item.heroName, 60);
    const totalMatches = cleanNonNegativeInteger(item.totalMatches);
    const advantageIndex = cleanNumber(item.advantageIndex);
    if (!heroName || totalMatches === null || advantageIndex === null) return [];
    return [{ heroName, totalMatches, advantageIndex }];
  });
}

function cleanSynergies(value: unknown): SynergyRelation[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (!isRecord(item)) return [];
    const heroName = cleanString(item.heroName, 60);
    const totalMatches = cleanNonNegativeInteger(item.totalMatches);
    const synergyIndex = cleanNumber(item.synergyIndex);
    if (!heroName || totalMatches === null || synergyIndex === null) return [];
    return [{ heroName, totalMatches, synergyIndex }];
  });
}

export async function fetchHeroAnalysis(heroId: number): Promise<AnalysisPayload> {
  const payload = await fetchUpstreamJson(
    `/api/hero/analysis?heroId=${encodeURIComponent(String(heroId))}`,
  );
  if (!isRecord(payload)) {
    throw new TianyuanzhiyiError("天元之弈英雄关系数据格式异常。", {
      endpoint: "/api/hero/analysis",
    });
  }

  return {
    source: TIANYUAN_SOURCE.name,
    sourceUrl: TIANYUAN_SOURCE.url,
    sourceMeta: TIANYUAN_SOURCE,
    mode: TIANYUAN_MODE,
    fetchedAt: new Date().toISOString(),
    queryDate: null,
    stale: false,
    fallback: { used: false, days: 0, attemptedDates: [] },
    dataWindow: "近30天 · 全部分路（上游未提供精确查询日期）",
    heroId: cleanPositiveInteger(payload.heroId) ?? heroId,
    counters: cleanCounters(payload.counters),
    counteredBy: cleanCounters(payload.counteredBy),
    goodSynergies: cleanSynergies(payload.goodSynergies),
    badSynergies: cleanSynergies(payload.badSynergies),
  };
}

export function parseHeroId(value: string | null): number | null {
  if (!value || !/^\d{1,6}$/.test(value)) return null;
  const heroId = Number(value);
  return Number.isSafeInteger(heroId) && heroId > 0 ? heroId : null;
}
