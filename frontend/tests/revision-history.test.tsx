import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer } from "node:http";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import History, { metadata } from "../app/s/[space]/history/[[...slug]]/page";
import Article from "../app/s/[space]/read/[[...slug]]/page";
import { historyHref } from "../lib/format";

async function serve(
  t: test.TestContext,
  answer: (path: string, route: string | null) => [number, unknown],
) {
  const server = createServer((request, response) => {
    const url = new URL(request.url!, "http://localhost");
    const [status, body] = answer(url.pathname, url.searchParams.get("route"));
    response.statusCode = status;
    response.setHeader("Content-Type", "application/json");
    response.end(JSON.stringify(body));
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const previous = process.env.POKETTO_API_BASE_URL;
  t.after(() => {
    if (previous === undefined) delete process.env.POKETTO_API_BASE_URL;
    else process.env.POKETTO_API_BASE_URL = previous;
    server.closeAllConnections();
    server.close();
  });
  const address = server.address();
  assert.ok(address && typeof address !== "string");
  process.env.POKETTO_API_BASE_URL = `http://127.0.0.1:${address.port}`;
}

const page = (space: string, slug: string[]) =>
  History({ params: Promise.resolve({ space, slug }) });

test("the history page compares the two newest versions and stays out of search", async (t) => {
  await serve(t, (path, route) => {
    assert.equal(path, "/api/public/spaces/home/history");
    assert.equal(route, "/雨后");
    return [
      200,
      {
        route: "/雨后",
        title: "雨后",
        complete: false,
        versions: [
          { savedAt: "2026-09-01T08:00:00Z", body: "第一行\n旧的说法\n" },
          { savedAt: "2026-09-02T09:30:00Z", body: "第一行\n新的说法\n" },
        ],
      },
    ];
  });
  const html = renderToStaticMarkup(
    await page("home", [encodeURIComponent("雨后")]),
  );
  assert.match(html, /公开以来共 2 个版本。可能还有更早的版本没有列出/);
  assert.match(
    html,
    /<option value="0" selected="">第 1 版 · 2026年9月1日 08:00 UTC/,
  );
  assert.match(
    html,
    /<option value="1" selected="">第 2 版 · 2026年9月2日 09:30 UTC/,
  );
  assert.match(
    html,
    /history-line removed"><span aria-hidden="true">− <\/span>旧的说法/,
  );
  assert.match(
    html,
    /history-line added"><span aria-hidden="true">\+ <\/span>新的说法/,
  );
  assert.match(html, /href="\/s\/home\/read\/%E9%9B%A8%E5%90%8E"/);
  assert.deepEqual(metadata.robots, { index: false, follow: true });
});

test("a hidden history is not found and an unavailable one asks for a retry", async (t) => {
  let status = 404;
  await serve(t, () => [status, {}]);
  await assert.rejects(page("home", ["essay"]), /NEXT_HTTP_ERROR_FALLBACK;404/);
  status = 503;
  assert.match(
    renderToStaticMarkup(await page("home", ["essay"])),
    /修订历史暂时读不到，请稍后刷新重试。/,
  );
});

test("the article footer links history only where the space shows it", async (t) => {
  let shown = false;
  await serve(t, (path, route) => {
    if (path === "/api/public/spaces/home")
      return [200, { slug: "home", displayName: "家", history: shown }];
    if (path.endsWith("/views")) return [404, {}];
    return [
      200,
      {
        route,
        title: "文章",
        body: "正文",
        tags: [],
        createdAt: "2026-09-01T00:00:00Z",
        updatedAt: "2026-09-01T00:00:00Z",
        folderPage: false,
        images: {},
        links: {},
        gallery: [],
      },
    ];
  });
  const render = async () =>
    renderToStaticMarkup(
      await Article({
        params: Promise.resolve({ space: "home", slug: ["essay"] }),
      }),
    );
  assert.doesNotMatch(await render(), /修订历史/);
  shown = true;
  assert.match(
    await render(),
    new RegExp(`<a href="${historyHref("/essay", "home")}">修订历史</a>`),
  );
  assert.equal(
    historyHref("/雨后/a b", "home"),
    "/s/home/history/%E9%9B%A8%E5%90%8E/a%20b",
  );
});
