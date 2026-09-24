import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer } from "node:http";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import Article from "../app/s/[space]/read/[[...slug]]/page";
import { Markdown } from "../components/markdown";
import { readingGuide, readingMinutes } from "../lib/reading";

const body = [
  "# 雨后",
  "开头一段。",
  "## 背景",
  "### 细节",
  "#### 不列出",
  "## 背景",
  "正文[^1]。",
  "[^1]: 注释。",
].join("\n\n");

test("the contents list the two shallowest levels with the page's own anchors", () => {
  const { contents } = readingGuide(body, "雨后");
  assert.deepEqual(
    contents.map(({ text, level }) => [text, level]),
    [
      ["背景", 0],
      ["细节", 1],
      ["背景", 0],
    ],
  );
  const html = renderToStaticMarkup(
    <Markdown source={body} pageTitle="雨后" />,
  );
  for (const { id } of contents) assert.match(html, new RegExp(`id="${id}"`));
  assert.notEqual(contents[0].id, contents[2].id);

  // A leading heading that is not the title stays in the list.
  assert.equal(readingGuide(body, "别的标题").contents[0].text, "雨后");
});

test("reading minutes count CJK characters and other words separately", () => {
  assert.equal(readingMinutes(""), 1);
  assert.equal(readingMinutes("字".repeat(1200)), 3);
  assert.equal(readingMinutes("word ".repeat(400)), 2);
  assert.equal(readingMinutes("字".repeat(400) + " word".repeat(200)), 2);
});

test("only fenced code that names its language is highlighted", () => {
  const html = renderToStaticMarkup(
    <Markdown
      source={[
        "```ts",
        "const answer = 42;",
        "```",
        "```",
        "const plain = 1;",
        "```",
        "```not-a-language",
        "still fine",
        "```",
      ].join("\n")}
    />,
  );
  assert.match(html, /<span class="hljs-keyword">const<\/span>/);
  assert.match(html, /<span class="hljs-number">42<\/span>/);
  assert.match(html, /<code>const plain = 1;\n<\/code>/);
  assert.match(html, /still fine/);
});

test("a long article shows its contents and reading time; a short one only the time", async (t) => {
  let source = body;
  const server = createServer((request, response) => {
    const url = new URL(request.url!, "http://localhost");
    response.setHeader("Content-Type", "application/json");
    if (url.pathname === "/api/public/spaces/home")
      return response.end(
        JSON.stringify({ slug: "home", displayName: "三里屯分部" }),
      );
    response.end(
      JSON.stringify({
        route: "/note",
        title: "雨后",
        body: source,
        tags: [],
        authorName: "作者",
        createdAt: "2026-09-14T00:00:00Z",
        updatedAt: "2026-09-15T00:00:00Z",
        folderPage: false,
        images: {},
        links: {},
        gallery: [],
        navigation: { entries: [], available: true, memberships: [] },
      }),
    );
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const address = server.address();
  assert.ok(address && typeof address !== "string");
  const previous = process.env.POKETTO_API_BASE_URL;
  process.env.POKETTO_API_BASE_URL = `http://127.0.0.1:${address.port}`;
  t.after(() => {
    if (previous === undefined) delete process.env.POKETTO_API_BASE_URL;
    else process.env.POKETTO_API_BASE_URL = previous;
    server.closeAllConnections();
    server.close();
  });
  const render = async () =>
    renderToStaticMarkup(
      await Article({
        params: Promise.resolve({ space: "home", slug: ["note"] }),
      }),
    );

  const long = await render();
  assert.match(long, /约 1 分钟/);
  assert.match(long, /class="read-layout has-rail"/);
  assert.equal(long.match(/aria-label="文章目录"/g)?.length, 2);
  assert.match(long, /href="#poketto-heading-%E7%BB%86%E8%8A%82"/);

  source = "## 只有一个标题\n\n短文。";
  const short = await render();
  assert.match(short, /约 1 分钟/);
  assert.doesNotMatch(short, /文章目录/);
  assert.match(short, /class="read-layout"/);
});
