import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer } from "node:http";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import { PublicSpacePage } from "../components/public-space";
import Article, {
  generateMetadata,
} from "../app/s/[space]/read/[[...slug]]/page";
import DefaultArticle from "../app/read/[[...slug]]/page";
import { articleHref } from "../lib/format";

function document(route: string) {
  return {
    route,
    title: "路径验收文章",
    body: "# 正文标题\n\n正文应该出现在初始 HTML 中。",
    tags: [],
    createdAt: "2026-09-01T00:00:00Z",
    updatedAt: "2026-09-01T00:00:00Z",
    folderPage: false,
    images: {},
    links: {},
    gallery: [],
  };
}

test("article page sends decoded route bytes to HTTP API while metadata keeps literal filenames", async (t) => {
  const routes = [
    "/随记/雨后",
    "/笔记/空 格",
    "/百分号/100%",
    "/百分号/%E9%9B%A8",
    "/井号/#标题",
    "/问号/问?",
    "/加号/a+b",
    "/",
  ];
  const requests: string[] = [];
  const server = createServer((request, response) => {
    const url = new URL(request.url!, "http://localhost");
    if (url.pathname === "/api/public/spaces/second-site") {
      response.setHeader("Content-Type", "application/json");
      response.end(
        JSON.stringify({ slug: "second-site", displayName: "第二个空间" }),
      );
      return;
    }
    assert.equal(url.pathname, "/api/public/spaces/second-site/document");
    const route = url.searchParams.get("route")!;
    requests.push(route);
    response.setHeader("Content-Type", "application/json");
    response.statusCode = routes.includes(route) ? 200 : 404;
    response.end(JSON.stringify(document(route)));
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
  for (const route of routes) {
    // These are the distinct params observed at Next's page and metadata entries.
    const href = articleHref(route);
    const pageSlug =
      href === "/read" ? [] : href.slice("/read/".length).split("/");
    const metadataSlug = route === "/" ? [] : route.slice(1).split("/");
    const html = renderToStaticMarkup(
      await Article({
        params: Promise.resolve({ space: "second-site", slug: pageSlug }),
      }),
    );
    assert.match(html, /<article class="reading-shell">/);
    assert.match(html, /正文应该出现在初始 HTML 中。/);
    assert.deepEqual(
      await generateMetadata({
        params: Promise.resolve({ space: "second-site", slug: metadataSlug }),
      }),
      { title: "路径验收文章" },
    );
    assert.deepEqual(requests.splice(0), [route, route]);
  }
});

test("article page rejects malformed escapes and encoded path separators before HTTP", async () => {
  for (const segment of [
    "%",
    "%E9",
    "%2F",
    "%5C",
    "%00",
    "%2e%2e",
    "%2e",
    "",
  ]) {
    await assert.rejects(
      Article({
        params: Promise.resolve({ space: "second-site", slug: [segment] }),
      }),
      /NEXT_HTTP_ERROR_FALLBACK;404/,
    );
  }
});

test("home and article keep text and empty gallery status in their initial HTML", async (t) => {
  let galleryStatus = "PARTIAL";
  const server = createServer((request, response) => {
    const url = new URL(request.url!, "http://localhost");
    response.setHeader("Content-Type", "application/json");
    response.end(
      JSON.stringify(
        url.pathname === "/api/public/spaces/home" ||
          url.pathname === "/api/public/spaces/second-site"
          ? { slug: "home", displayName: "Home" }
          : url.pathname.endsWith("/documents")
            ? { commit: "fixture", items: [], total: 0, offset: 0, limit: 12 }
            : {
                ...document(url.searchParams.get("route")!),
                commit: "fixture",
                folderPage: true,
                galleryStatus,
              },
      ),
    );
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
  for (const [status, message] of [
    ["PARTIAL", "部分同目录图片未展示。"],
    ["UNAVAILABLE", "同目录图片暂时无法加载。"],
  ]) {
    galleryStatus = status;
    const values = [
      await PublicSpacePage({ slug: "home", view: "home", parameters: {} }),
      await Article({
        params: Promise.resolve({ space: "second-site", slug: ["album"] }),
      }),
    ];
    for (const value of values) {
      const html = renderToStaticMarkup(value);
      assert.match(html, /正文应该出现在初始 HTML 中。/);
      assert.ok(html.includes(message));
      assert.doesNotMatch(html, /<img/);
    }
  }
});

test("default article redirect preserves literal route bytes and never redirects a missing article", async (t) => {
  const server = createServer((request, response) => {
    const url = new URL(request.url!, "http://localhost");
    response.setHeader("Content-Type", "application/json");
    if (url.pathname === "/api/public/default-space") {
      response.end(JSON.stringify({ slug: "home", displayName: "Home" }));
    } else if (
      url.pathname === "/api/public/document" &&
      url.searchParams.get("route") === "/100%/#标题"
    ) {
      response.end(JSON.stringify(document("/100%/#标题")));
    } else {
      response.statusCode = 404;
      response.end("{}");
    }
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
  await assert.rejects(
    DefaultArticle({
      params: Promise.resolve({ slug: ["100%25", "%23%E6%A0%87%E9%A2%98"] }),
    }),
    (error: unknown) =>
      !!error &&
      typeof error === "object" &&
      "digest" in error &&
      String(error.digest).includes(
        "/s/home/read/100%25/%23%E6%A0%87%E9%A2%98;307;",
      ),
  );
  await assert.rejects(
    DefaultArticle({ params: Promise.resolve({ slug: ["missing"] }) }),
    /NEXT_HTTP_ERROR_FALLBACK;404/,
  );
});
