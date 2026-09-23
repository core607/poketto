import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer } from "node:http";
import test, { type TestContext } from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import Home, { metadata } from "../app/page";
import { plainSummary } from "../lib/summary";

test("summaries start at the prose, skipping the heading, figure and its caption", () => {
  const body = [
    "# 谷歌向全体工程师开放竞品 Claude",
    "![谷歌内部开发平台的界面示意](cover.png)",
    "*配图：用户提供的示意图，非产品实际截图。*",
    "**TL;DR**：谷歌已正式允许全体内部工程师使用 [Claude](https://claude.example) 模型。",
    "## 背景",
    "长期以来，谷歌一直对外部工具施加严格限制。",
  ].join("\n\n");

  assert.equal(
    plainSummary(body, "谷歌向全体工程师开放竞品 Claude"),
    "TL;DR：谷歌已正式允许全体内部工程师使用 Claude 模型。 长期以来，谷歌一直对外部工具施加严格限制。",
  );
});

test("bold prose under an image and a leading rule are kept as prose", () => {
  assert.equal(
    plainSummary("![图](a.png)\n\n**这一整段加粗的正文不是图片说明。**"),
    "这一整段加粗的正文不是图片说明。",
  );
  assert.equal(
    plainSummary("---\n\n第一段正文。\n\n---\n\n第二段正文。"),
    "第一段正文。 第二段正文。",
  );
});

test("long summaries end at a nearby sentence or an ellipsis", () => {
  const sentence = "这是一句足够长的正文，用来确认摘要会在句号处收尾。";
  assert.equal(plainSummary(sentence.repeat(10), "", 60), sentence.repeat(2));
  assert.equal(
    plainSummary("没有标点".repeat(40), "", 20),
    "没有标点".repeat(5) + "…",
  );
});

test("a fresh homepage answers with its batch instead of redirecting", async (t: TestContext) => {
  const server = createServer((request, response) => {
    const url = new URL(request.url!, "http://localhost");
    assert.equal(url.pathname, "/api/public/discovery");
    assert.equal(url.searchParams.get("batch"), null);
    response.setHeader("Content-Type", "application/json");
    response.end(
      JSON.stringify({
        batch: "fresh-batch",
        expiresAt: "2026-09-24T01:00:00Z",
        offset: 0,
        limit: 6,
        nextOffset: 6,
        previousOffset: null,
        items: [
          {
            space: "space",
            spaceName: "公开空间",
            authorName: "作者",
            route: "/note",
            title: "第一篇记录",
            snippet: "一段公开摘要",
            tags: [],
            createdAt: "2026-09-01T00:00:00Z",
            folderPage: false,
            album: false,
            collection: false,
            cover: null,
          },
        ],
      }),
    );
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const previous = process.env.POKETTO_API_BASE_URL;
  const address = server.address();
  assert.ok(address && typeof address !== "string");
  process.env.POKETTO_API_BASE_URL = `http://127.0.0.1:${address.port}`;
  t.after(() => {
    if (previous === undefined) delete process.env.POKETTO_API_BASE_URL;
    else process.env.POKETTO_API_BASE_URL = previous;
    server.closeAllConnections();
    server.close();
  });

  const html = renderToStaticMarkup(
    await Home({ searchParams: Promise.resolve({}) }),
  );
  assert.match(html, /第一篇记录/);
  assert.match(html, /href="\/\?batch=fresh-batch&amp;offset=6"/);
  assert.deepEqual(metadata.alternates, { canonical: "/" });
});
