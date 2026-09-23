import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer } from "node:http";
import test, { type TestContext } from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import { PublicSpacePage } from "../components/public-space";
import Archive from "../app/archive/page";
import Search from "../app/search/page";
import Tags from "../app/tags/page";
import Home from "../app/page";

type Offset = string | string[] | undefined;
const pages = [
  {
    title: "全部记录",
    max: 10000,
    limit: "12",
    filter: {},
    render: (offset: Offset) =>
      PublicSpacePage({ slug: "home", view: "home", parameters: { offset } }),
  },
  {
    title: "按时间翻阅",
    max: 10000,
    limit: "100",
    filter: {},
    render: (offset: Offset) =>
      PublicSpacePage({
        slug: "home",
        view: "archive",
        parameters: { offset },
      }),
  },
  {
    title: "「分页文字」",
    max: 10000,
    limit: "12",
    filter: { query: "分页文字" },
    render: (offset: Offset) =>
      Search({ searchParams: Promise.resolve({ query: "分页文字", offset }) }),
  },
  {
    title: "#分页标签",
    max: 10000,
    limit: "12",
    filter: { tag: "分页标签" },
    render: (offset: Offset) =>
      PublicSpacePage({
        slug: "home",
        view: "tags",
        parameters: { tag: "分页标签", offset },
      }),
  },
  {
    title: "所有标签",
    max: 320000,
    limit: "100",
    filter: {},
    render: (offset: Offset) =>
      PublicSpacePage({ slug: "home", view: "tags", parameters: { offset } }),
  },
];

async function backend(t: TestContext) {
  const requests: URL[] = [];
  const server = createServer((request, response) => {
    const url = new URL(request.url!, "http://localhost");
    response.setHeader("Content-Type", "application/json");
    if (url.pathname === "/api/public/default-space") {
      response.end(JSON.stringify({ slug: "home", displayName: "Home" }));
      return;
    }
    if (url.pathname === "/api/public/spaces/home") {
      response.end(JSON.stringify({ slug: "home", displayName: "Home" }));
      return;
    }
    if (url.pathname.endsWith("/document")) {
      response.writeHead(404).end("{}");
      return;
    }
    requests.push(url);
    const offset = Number(url.searchParams.get("offset"));
    const maximum = url.pathname.endsWith("/tags") ? 320000 : 10000;
    // Queries use UTF-16 bounds; tags share the repository's Unicode code-point bound.
    if (
      !Number.isInteger(offset) ||
      offset < 0 ||
      offset > maximum ||
      (url.searchParams.get("query") ?? "").length > 200 ||
      [...(url.searchParams.get("tag") ?? "")].length > 64
    ) {
      response.writeHead(400).end("{}");
      return;
    }
    if (url.pathname === "/api/public/discovery") {
      const batch = url.searchParams.get("batch");
      if (batch === "expired" || batch === "mismatched-tag") {
        response.writeHead(batch === "expired" ? 410 : 400).end("{}");
        return;
      }
      response.end(
        JSON.stringify({
          batch,
          tag: url.searchParams.get("tag") ?? "",
          items: [],
          offset: 0,
          limit: 6,
          nextOffset: null,
          previousOffset: null,
        }),
      );
      return;
    }
    response.end(
      JSON.stringify({
        commit: "snapshot",
        items: [],
        tags: [],
        total: 200,
        offset,
        limit: Number(url.searchParams.get("limit")),
      }),
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
  return requests;
}

test("all public page consumers fall back to the first page for invalid or repeated offsets", async (t) => {
  const requests = await backend(t);
  for (const page of pages) {
    for (const offset of [
      undefined,
      "abc",
      "-1",
      "1.5",
      "1e2",
      "+1",
      " ",
      "",
      String(page.max + 1),
      "2147483648",
      "9".repeat(400),
      ["1", "2"],
      ["12"],
    ]) {
      const html = renderToStaticMarkup(await page.render(offset));
      assert.ok(html.includes(page.title));
      assert.equal(requests.length, 1);
      const [request] = requests.splice(0);
      assert.equal(
        request.searchParams.get("offset"),
        "0",
        `${page.title}: ${offset}`,
      );
      assert.equal(request.searchParams.get("limit"), page.limit);
      for (const [key, value] of Object.entries(page.filter))
        assert.equal(request.searchParams.get(key), value);
      assert.doesNotMatch(html, /上一页/);
    }
  }
});

test("public page consumers retain valid offsets through each backend maximum", async (t) => {
  const requests = await backend(t);
  for (const page of pages) {
    for (const offset of ["0", "12", "00012", String(page.max)]) {
      const html = renderToStaticMarkup(await page.render(offset));
      assert.ok(html.includes(page.title));
      assert.equal(requests.length, 1);
      const [request] = requests.splice(0);
      assert.equal(request.searchParams.get("offset"), String(Number(offset)));
      if (Number(offset) > 0) assert.match(html, /上一页/);
    }
  }
});

test("search rejects overlong UTF-16 input locally while preserving the entered query", async (t) => {
  const requests = await backend(t);
  for (const query of [
    "a".repeat(200),
    "😀".repeat(100),
    ["a".repeat(99), "b".repeat(100)],
  ]) {
    const html = renderToStaticMarkup(
      await Search({ searchParams: Promise.resolve({ query }) }),
    );
    assert.equal(requests.length, 1);
    assert.equal(requests.shift()!.searchParams.get("query"), String(query));
    assert.ok(html.includes(`value="${String(query)}"`));
    assert.doesNotMatch(html, /role="alert"/);
  }
  for (const query of [
    "a".repeat(201),
    "😀".repeat(100) + "a",
    ["a".repeat(100), "b".repeat(100)],
  ]) {
    const html = renderToStaticMarkup(
      await Search({ searchParams: Promise.resolve({ query }) }),
    );
    assert.ok(html.includes(`value="${String(query)}"`));
    assert.match(html, /role="alert"/);
    assert.match(html, /搜索内容过长，请缩短后再试。/);
    assert.match(html, /aria-invalid="true"/);
    assert.equal(requests.length, 0);
  }
});

test("tag filters preserve the repository's 64-code-point boundary and reject oversized tags before HTTP", async (t) => {
  const requests = await backend(t);
  const tagPage = (tag: string | string[]) =>
    PublicSpacePage({ slug: "home", view: "tags", parameters: { tag } });
  for (const tag of [
    "a".repeat(64),
    "😀".repeat(64),
    ["a".repeat(31), "b".repeat(32)],
  ]) {
    const html = renderToStaticMarkup(await tagPage(tag));
    assert.equal(requests.length, 1);
    assert.equal(requests.shift()!.searchParams.get("tag"), String(tag));
    if (typeof tag === "string") assert.ok(html.includes(`#${tag}`));
  }
  for (const tag of [
    "a".repeat(65),
    "😀".repeat(64) + "a",
    ["a".repeat(32), "b".repeat(32)],
  ]) {
    const html = renderToStaticMarkup(await tagPage(tag));
    assert.match(html, /搜索内容或标签过长/);
    assert.equal(requests.length, 0);
  }
});

test("root tag and archive pages redirect into the default space with their offsets", async (t) => {
  await backend(t);
  const target = async (page: Promise<unknown>) => {
    try {
      await page;
    } catch (error) {
      const digest = String((error as { digest?: string }).digest);
      assert.match(digest, /^NEXT_REDIRECT;/);
      return digest.split(";")[2];
    }
    assert.fail("expected a redirect");
  };
  assert.equal(
    await target(
      Tags({ searchParams: Promise.resolve({ tag: "旅行", offset: "12" }) }),
    ),
    "/s/home/tags?tag=%E6%97%85%E8%A1%8C&offset=12",
  );
  assert.equal(
    await target(Tags({ searchParams: Promise.resolve({ tagOffset: "100" }) })),
    "/s/home/tags?offset=100",
  );
  assert.equal(
    await target(Archive({ searchParams: Promise.resolve({ offset: "100" }) })),
    "/s/home/archive?offset=100",
  );
});

test("discovery renders valid emoji tags and offers recovery for rejected parameters and expired batches", async (t) => {
  await backend(t);
  const tag = "😸".repeat(64);
  const html = renderToStaticMarkup(
    await Home({ searchParams: Promise.resolve({ batch: "kept", tag }) }),
  );
  assert.ok(html.includes(`正在发现「${tag}」相关内容。`));
  assert.ok(html.includes('maxLength="128"'));
  assert.ok(html.includes('pattern=".{0,64}"'));
  for (const parameters of [
    { batch: "kept", tag: tag + "x" },
    { batch: "mismatched-tag", tag: "new" },
    { batch: "expired" },
  ]) {
    const page = renderToStaticMarkup(
      await Home({ searchParams: Promise.resolve(parameters) }),
    );
    assert.match(page, /开始新一批/);
    assert.match(
      page,
      parameters.batch === "expired" ? /浏览记录已过期/ : /标签或翻页参数无效/,
    );
    assert.doesNotMatch(page, /内容暂时无法读取/);
    if (parameters.tag) {
      assert.ok(page.includes(`value="${parameters.tag}"`));
      assert.ok(page.includes('action="/"'));
    }
  }
});
