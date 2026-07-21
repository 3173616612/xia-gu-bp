import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import test from "node:test";

const templateRoot = new URL("../", import.meta.url);

async function render(pathname = "/") {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request(`http://localhost${pathname}`, { headers: { accept: "text/html", host: "localhost" } }),
    { ASSETS: { fetch: async () => new Response("Not found", { status: 404 }) } },
    { waitUntil() {}, passThroughOnException() {} },
  );
}

test("server-renders the finished BP site metadata", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(html, /<html[^>]+lang="zh-CN"/i);
  assert.match(html, /<title>峡谷 BP · 王者荣耀实时选将助手<\/title>/i);
  assert.match(html, /实时对位数据与英雄梯度/);
  assert.match(html, /\/og\.png/);
  assert.doesNotMatch(html, /codex-preview|Your site is taking shape|Starter Project/i);
});

test("keeps the live data contract and removes the disposable starter", async () => {
  const [page, assistant, layout, packageJson, metaRoute, analysisRoute, socialCard] = await Promise.all([
    readFile(new URL("../app/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/BpAssistant.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/layout.tsx", import.meta.url), "utf8"),
    readFile(new URL("../package.json", import.meta.url), "utf8"),
    readFile(new URL("../app/api/meta/route.ts", import.meta.url), "utf8"),
    readFile(new URL("../app/api/analysis/route.ts", import.meta.url), "utf8"),
    access(new URL("../public/og.png", import.meta.url)),
  ]);

  assert.equal(socialCard, undefined);
  assert.match(page, /<BpAssistant \/>/);
  assert.match(assistant, /推荐综合阵容提升、对敌净分、队友配合与当前梯度/);
  assert.doesNotMatch(assistant, /本次权重/);
  assert.match(assistant, /calculateCounterLiftScore/);
  assert.match(assistant, /goodSynergies/);
  assert.match(assistant, /counterPenalty/);
  assert.match(assistant, /counteredBy/);
  assert.match(assistant, /const \[bannedHeroes, setBannedHeroes\]/);
  assert.match(assistant, /\.\.\.bannedHeroes/);
  assert.match(assistant, /设为已 Ban/);
  assert.doesNotMatch(assistant, /calculateTacticalFit|机制补正|团队解控/);
  assert.match(assistant, /巅峰千强/);
  assert.match(layout, /\/og\.png/);
  assert.match(metaRoute, /force-dynamic/);
  assert.match(analysisRoute, /heroId/);
  assert.doesNotMatch(packageJson, /react-loading-skeleton/);
  await assert.rejects(access(new URL("../app/_sites-preview/", import.meta.url)));
  await assert.rejects(access(new URL("../public/favicon.svg", import.meta.url)));
});
