import {
  fetchMeta,
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

export async function GET() {
  try {
    const payload = await fetchMeta();
    return Response.json(payload, { headers: NO_STORE_HEADERS });
  } catch (error) {
    const message =
      error instanceof TianyuanzhiyiError
        ? error.message
        : "加载英雄与梯度数据时发生未知错误，请稍后重试。";
    const status = error instanceof TianyuanzhiyiError ? error.httpStatus : 500;

    return Response.json(
      {
        error: message,
        code: status === 404 ? "UPSTREAM_NOT_FOUND" : "UPSTREAM_UNAVAILABLE",
        source: TIANYUAN_SOURCE.name,
        sourceUrl: TIANYUAN_SOURCE.url,
        mode: TIANYUAN_MODE,
        fetchedAt: new Date().toISOString(),
      },
      { status, headers: NO_STORE_HEADERS },
    );
  }
}
