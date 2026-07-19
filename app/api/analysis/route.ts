import {
  fetchHeroAnalysis,
  parseHeroId,
  TIANYUAN_MODE,
  TIANYUAN_SOURCE,
  TianyuanzhiyiError,
} from "@/lib/tianyuanzhiyi";

export const runtime = "edge";
export const dynamic = "force-dynamic";

const NO_STORE_HEADERS = {
  "Cache-Control": "no-store, max-age=0",
  "CDN-Cache-Control": "no-store",
};

export async function GET(request: Request) {
  const heroId = parseHeroId(new URL(request.url).searchParams.get("heroId"));
  if (heroId === null) {
    return Response.json(
      {
        error: "heroId 必须是 1 至 6 位的正整数。",
        code: "INVALID_HERO_ID",
        source: TIANYUAN_SOURCE.name,
        sourceUrl: TIANYUAN_SOURCE.url,
        mode: TIANYUAN_MODE,
        fetchedAt: new Date().toISOString(),
      },
      { status: 400, headers: NO_STORE_HEADERS },
    );
  }

  try {
    const payload = await fetchHeroAnalysis(heroId);
    return Response.json(payload, { headers: NO_STORE_HEADERS });
  } catch (error) {
    const message =
      error instanceof TianyuanzhiyiError
        ? error.message
        : "加载英雄克制关系时发生未知错误，请稍后重试。";
    const status = error instanceof TianyuanzhiyiError ? error.httpStatus : 500;

    return Response.json(
      {
        error: message,
        code: status === 404 ? "HERO_ANALYSIS_NOT_FOUND" : "UPSTREAM_UNAVAILABLE",
        source: TIANYUAN_SOURCE.name,
        sourceUrl: TIANYUAN_SOURCE.url,
        mode: TIANYUAN_MODE,
        fetchedAt: new Date().toISOString(),
        heroId,
      },
      { status, headers: NO_STORE_HEADERS },
    );
  }
}
