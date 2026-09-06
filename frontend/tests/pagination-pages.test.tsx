import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer } from "node:http";
import test, { type TestContext } from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import Home from "../app/page";
import Archive from "../app/archive/page";
import Search from "../app/search/page";
import Tags from "../app/tags/page";

type Offset = string | string[] | undefined;
const pages = [
  {
    title: "最近的记录",
    max: 10000,
    limit: "12",
    filter: {},
    render: (offset: Offset) =>
      Home({ searchParams: Promise.resolve({ offset }) }),
  },
  {
    title: "归档",
    max: 10000,
    limit: "100",
    filter: {},
    render: (offset: Offset) =>
      Archive({ searchParams: Promise.resolve({ offset }) }),
  },
  {
    title: "搜索记录",
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
      Tags({ searchParams: Promise.resolve({ tag: "分页标签", offset }) }),
  },
  {
    title: "标签",
    max: 320000,
    limit: "100",
    filter: {},
    render: (tagOffset: Offset) =>
      Tags({ searchParams: Promise.resolve({ tagOffset }) }),
  },
];

async function backend(t: TestContext) {
  const requests: URL[] = [];
  const server = createServer((request, response) => {
    const url = new URL(request.url!, "http://localhost");
    response.setHeader("Content-Type", "application/json");
    if (url.pathname === "/api/public/document") {
      response.writeHead(404).end("{}");
      return;
    }
    requests.push(url);
    const offset = Number(url.searchParams.get("offset"));
    const maximum = url.pathname === "/api/public/tags" ? 320000 : 10000;
    // PublicDocuments rejects offsets beyond these distinct endpoint bounds.
    if (!Number.isInteger(offset) || offset < 0 || offset > maximum) {
      response.writeHead(400).end("{}");
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
      assert.doesNotMatch(html, /← 上一页/);
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
      if (Number(offset) > 0) assert.match(html, /← 上一页/);
    }
  }
});
