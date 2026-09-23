import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer } from "node:http";
import test, { type TestContext } from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import { GET as cover } from "../app/s/[space]/cover/[[...slug]]/route";
import Article, {
  generateMetadata,
} from "../app/s/[space]/read/[[...slug]]/page";
import { coverHref, routeFromSegments } from "../lib/format";

async function backend(
  t: TestContext,
  handle: (url: URL) => { status: number; type?: string; body?: string },
) {
  const server = createServer((request, response) => {
    const answer = handle(new URL(request.url!, "http://localhost"));
    response.statusCode = answer.status;
    if (answer.type) response.setHeader("Content-Type", answer.type);
    response.end(answer.body ?? "");
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const address = server.address();
  assert.ok(address && typeof address !== "string");
  const previous = {
    api: process.env.POKETTO_API_BASE_URL,
    origin: process.env.POKETTO_PUBLIC_URL,
  };
  process.env.POKETTO_API_BASE_URL = `http://127.0.0.1:${address.port}`;
  process.env.POKETTO_PUBLIC_URL = "https://poketto.example";
  t.after(() => {
    for (const [key, value] of [
      ["POKETTO_API_BASE_URL", previous.api],
      ["POKETTO_PUBLIC_URL", previous.origin],
    ] as const) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
    server.closeAllConnections();
    server.close();
  });
}

test("cover addresses sit beside reading addresses and decode like them", () => {
  assert.equal(
    coverHref("/随记/雨后", "home"),
    "/s/home/cover/%E9%9A%8F%E8%AE%B0/%E9%9B%A8%E5%90%8E",
  );
  assert.equal(coverHref("/", "home"), "/s/home/cover");
  assert.equal(routeFromSegments(["%E9%9B%A8", "100%25"]), "/雨/100%");
  assert.equal(routeFromSegments([]), "/");
  for (const bad of [["%"], ["%2F"], [".."], [""], ["%00"]])
    assert.equal(routeFromSegments(bad), null);
});

test("the cover address serves the thumbnail, redirects when there is none and 404s when withdrawn", async (t) => {
  const routes: string[] = [];
  await backend(t, (url) => {
    const route = url.searchParams.get("route")!;
    routes.push(route);
    assert.equal(url.pathname, "/api/public/spaces/home/cover");
    if (route === "/雨后/100%")
      return { status: 200, type: "image/jpeg", body: "jpeg-bytes" };
    if (route === "/plain") return { status: 204 };
    return { status: 404, type: "application/json", body: "{}" };
  });
  const call = (path: string) =>
    cover(new Request("https://poketto.example" + path), {
      params: Promise.resolve({ space: "home" }),
    });

  const image = await call("/s/home/cover/%E9%9B%A8%E5%90%8E/100%25");
  assert.equal(image.status, 200);
  assert.equal(image.headers.get("Content-Type"), "image/jpeg");
  assert.equal(image.headers.get("Cache-Control"), "public, max-age=300");
  assert.equal(await image.text(), "jpeg-bytes");

  const fallback = await call("/s/home/cover/plain");
  assert.equal(fallback.status, 307);
  assert.equal(fallback.headers.get("Location"), "/share.png");

  assert.equal((await call("/s/home/cover/withdrawn")).status, 404);
  assert.equal((await call("/s/home/cover/%2e%2e")).status, 404);
  assert.deepEqual(routes, ["/雨后/100%", "/plain", "/withdrawn"]);
});

test("an article declares its cover for previews and describes itself as structured data", async (t) => {
  await backend(t, (url) => {
    if (url.pathname === "/api/public/spaces/home")
      return {
        status: 200,
        type: "application/json",
        body: JSON.stringify({ slug: "home", displayName: "三里屯分部" }),
      };
    return {
      status: 200,
      type: "application/json",
      body: JSON.stringify({
        route: "/news/a",
        title: "标题 </script>",
        body: "# 标题\n\n![图](a.png)\n\n正文第一句。",
        tags: ["AI"],
        authorName: "仙女座话事人",
        createdAt: "2026-09-14T00:00:00Z",
        updatedAt: "2026-09-15T00:00:00Z",
        folderPage: false,
        images: {},
        links: {},
        gallery: [],
        navigation: { entries: [], available: true, memberships: [] },
      }),
    };
  });

  const metadata = await generateMetadata({
    params: Promise.resolve({ space: "home", slug: ["news", "a"] }),
  });
  assert.deepEqual(metadata.openGraph?.images, [
    { url: "/s/home/cover/news/a", alt: "标题 </script>" },
  ]);
  assert.deepEqual(metadata.twitter?.images, ["/s/home/cover/news/a"]);

  const html = renderToStaticMarkup(
    await Article({
      params: Promise.resolve({ space: "home", slug: ["news", "a"] }),
    }),
  );
  const block = html.match(
    /<script type="application\/ld\+json">([\s\S]*?)<\/script>/,
  );
  assert.ok(block, "structured data is present");
  assert.doesNotMatch(block[1], /<\/script>/);
  const data = JSON.parse(block[1]);
  assert.equal(data["@type"], "BlogPosting");
  assert.equal(data.headline, "标题 </script>");
  assert.equal(data.description, "正文第一句。");
  assert.equal(data.image, "https://poketto.example/s/home/cover/news/a");
  assert.equal(
    data.mainEntityOfPage,
    "https://poketto.example/s/home/read/news/a",
  );
  assert.deepEqual(data.author, { "@type": "Person", name: "仙女座话事人" });
});
