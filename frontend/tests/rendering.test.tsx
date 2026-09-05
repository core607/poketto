import assert from "node:assert/strict";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import { Markdown } from "../components/markdown";
import { Gallery } from "../components/gallery";
import { ArticleList } from "../components/articles";
import { articleHref, safeImage, safeLink, xml } from "../lib/format";
import { relativePath } from "../components/asset-picker";

test("raw HTML and script links cannot enter rendered Markdown", () => {
  const html = renderToStaticMarkup(
    <Markdown
      source={
        '# 标题\n\n<script>alert(1)</script>\n\n<img src=x onerror="alert(1)">\n\n[bad](javascript:alert%281%29)\n\n[ok](https://example.test)'
      }
    />,
  );
  assert.match(html, /<h1 id="poketto-heading-标题">标题<\/h1>/);
  assert.doesNotMatch(html, /<script|onerror|href="javascript:/);
  assert.match(html, /href="https:\/\/example.test"/);
});

test("authored image URLs never become network requests without backend resolution", () => {
  const html = renderToStaticMarkup(
    <Markdown source="![外链](https://remote.invalid/photo.png)\n\n![私有](../private/photo.png)" />,
  );
  assert.doesNotMatch(html, /<img|src=/);
  assert.match(html, /暂无可用预览/);
});

test("public and authenticated previews use separate approved image entrances", () => {
  const mapping = {
    "photo.png": "/api/public/assets/public-grant",
    "private.png": "/api/admin/assets/images/private-token",
  };
  const source = "![Public](photo.png)\n\n![Private](private.png)";
  const html = renderToStaticMarkup(
    <Markdown source={source} images={mapping} />,
  );
  assert.match(html, /src="\/api\/public\/assets\/public-grant"/);
  assert.doesNotMatch(html, /src="\/api\/admin/);
  const preview = renderToStaticMarkup(
    <Markdown source={source} images={mapping} preview />,
  );
  assert.match(preview, /src="\/api\/admin\/assets\/images\/private-token"/);
  assert.doesNotMatch(
    renderToStaticMarkup(
      <Gallery items={[{ src: mapping["private.png"], alt: "Private" }]} />,
    ),
    /<img/,
  );
});

test("resolved public routes and private editor links use their actual frontend namespaces", () => {
  const html = renderToStaticMarkup(
    <Markdown source="[Note](next.md)" links={{ "next.md": "/admin" }} />,
  );
  assert.match(html, /href="\/read\/admin"/);
  const preview = renderToStaticMarkup(
    <Markdown
      source="[Private](secret.md)"
      links={{ "secret.md": "/admin?path=private%2Fsecret.md" }}
      preview
    />,
  );
  assert.match(preview, /href="\/admin\?path=private%2Fsecret.md"/);
  assert.equal(articleHref("/城市/雨"), "/read/%E5%9F%8E%E5%B8%82/%E9%9B%A8");
  assert.equal(articleHref("/"), "/read");
});

test("safe URL rules reject protocols, external image grants, and normalized traversal", () => {
  for (const value of [
    "javascript:alert(1)",
    "data:text/html,x",
    "//evil.invalid",
    "https:\\evil.invalid",
    "java\nscript:alert(1)",
  ])
    assert.equal(safeLink(value), undefined);
  for (const value of [
    "https://example.test/api/public/assets/a",
    "/api/public/assets/../../../evil",
    "data:image/png;base64,a",
  ])
    assert.equal(safeImage(value), undefined);
  assert.equal(safeImage("/api/admin/assets/images/a"), undefined);
  assert.equal(
    safeImage("/api/admin/assets/images/a", true),
    "/api/admin/assets/images/a",
  );
  assert.equal(
    relativePath("笔记/旅行/index.md", "笔记/图片/雨.png"),
    "../图片/雨.png",
  );
  assert.equal(xml("<&\"'"), "&lt;&amp;&quot;&apos;");
});

test("article stream contains readable titles and links in initial server HTML", () => {
  const html = renderToStaticMarkup(
    <ArticleList
      page={{
        commit: "a",
        verifiedAt: "2026-09-05T00:00:00Z",
        expiresAt: "2026-09-05T01:00:00Z",
        total: 1,
        offset: 0,
        limit: 12,
        items: [
          {
            route: "/note",
            title: "一篇记录",
            tags: ["知识"],
            createdAt: "2026-09-01T00:00:00Z",
            updatedAt: "2026-09-01T00:00:00Z",
            snippet: "不需要 JavaScript 也能读到的文字。",
          },
        ],
      }}
    />,
  );
  assert.match(html, /一篇记录/);
  assert.match(html, /不需要 JavaScript/);
  assert.match(html, /href="\/read\/note"/);
  assert.doesNotMatch(html, /<script/);
});

test("backend destinations match URI-normalized Chinese spaces and encoded image names", () => {
  for (const authored of [
    "图片.png",
    "photo one.png",
    "%E5%9B%BE%E7%89%87.png",
    "photo%20one.png",
    "100%25.png",
  ]) {
    const html = renderToStaticMarkup(
      <Markdown
        source={`![图](<${authored}>)`}
        images={{ [authored]: "/api/public/assets/verified" }}
      />,
    );
    assert.match(html, /src="\/api\/public\/assets\/verified"/);
    assert.doesNotMatch(html, /暂无可用预览/);
  }
});

test("Chinese and encoded document links preserve route bytes and authored fragments", () => {
  for (const [authored, route, expected] of [
    ["中文.md", "/中文", "/read/%E4%B8%AD%E6%96%87"],
    ["file one.md", "/file one", "/read/file%20one"],
    ["file%20one.md", "/file one", "/read/file%20one"],
    ["#section", "#section", "#poketto-heading-section"],
    ["#正文", "#正文", "#poketto-heading-%E6%AD%A3%E6%96%87"],
    [
      "other.md#section",
      "/other#section",
      "/read/other#poketto-heading-section",
    ],
    [
      "中文.md#正文",
      "/中文#正文",
      "/read/%E4%B8%AD%E6%96%87#poketto-heading-%E6%AD%A3%E6%96%87",
    ],
    ["a%23b.md#part", "/a#b#part", "/read/a%23b#poketto-heading-part"],
    ["a%23b.md", "/a#b", "/read/a%23b"],
    ["100%25.md", "/100%", "/read/100%25"],
  ]) {
    const html = renderToStaticMarkup(
      <Markdown
        source={`[正文](<${authored}>)`}
        links={{ [authored]: route }}
      />,
    );
    assert.ok(html.includes(`href="${expected}"`), html);
  }
});

test("normalized private previews still require the authenticated image and editor entrances", () => {
  const source = "![图](<私有 图.png>)\n\n[笔记](私有.md#正文)";
  const images = { "私有 图.png": "/api/admin/assets/images/verified" };
  const links = {
    "私有.md#正文": "/admin?path=private%2F%E7%A7%81%E6%9C%89.md#正文",
  };
  const html = renderToStaticMarkup(
    <Markdown source={source} images={images} links={links} preview />,
  );
  assert.match(html, /src="\/api\/admin\/assets\/images\/verified"/);
  assert.match(
    html,
    /href="\/admin\?path=private%2F%E7%A7%81%E6%9C%89.md#poketto-heading-%E6%AD%A3%E6%96%87"/,
  );
  assert.doesNotMatch(
    renderToStaticMarkup(<Markdown source={source} images={images} />),
    /<img/,
  );
});

test("normalizing destinations does not admit unsafe schemes or unresolved image sources", () => {
  const html = renderToStaticMarkup(
    <Markdown source="[bad](javascript:alert%281%29)\n\n![bad](data:image/svg+xml,x)\n\n[bad](vbscript:test)" />,
  );
  assert.doesNotMatch(html, /<img|href=/);
  const unsafeMapping = renderToStaticMarkup(
    <Markdown
      source="[note](中文.md)"
      links={{ "中文.md": "javascript:alert(1)" }}
    />,
  );
  assert.doesNotMatch(unsafeMapping, /href=/);
});

test("heading IDs match same-page fragments including Chinese and repeated headings", () => {
  const source =
    "# A **small** note\n\n## 中文标题\n\n## 中文标题\n\n[one](#a-small-note) [two](#中文标题) [three](#中文标题-1)";
  const links = {
    "#a-small-note": "#a-small-note",
    "#中文标题": "#中文标题",
    "#中文标题-1": "#中文标题-1",
  };
  for (const preview of [false, true]) {
    const html = renderToStaticMarkup(
      <Markdown source={source} links={links} preview={preview} />,
    );
    const ids = [...html.matchAll(/<h[1-6] id="([^"]+)"/g)].map(
      (match) => match[1],
    );
    const anchors = [...html.matchAll(/href="#([^"]+)"/g)].map((match) =>
      decodeURIComponent(match[1]),
    );
    assert.deepEqual(ids, [
      "poketto-heading-a-small-note",
      "poketto-heading-中文标题",
      "poketto-heading-中文标题-1",
    ]);
    assert.deepEqual(anchors, ids);
    assert.equal(new Set(ids).size, ids.length);
  }
});

test("cross-page fragment points at the destination heading without changing external fragments", () => {
  const destination = renderToStaticMarkup(<Markdown source="## 中文标题" />);
  const source = renderToStaticMarkup(
    <Markdown
      source="[next](文档.md#中文标题) [external](https://example.test/#中文标题)"
      links={{ "文档.md#中文标题": "/文档#中文标题" }}
    />,
  );
  const internal = source.match(/href="(\/read\/[^"]+)"/)![1];
  const destinationId = destination.match(/id="([^"]+)"/)![1];
  assert.equal(decodeURIComponent(internal.split("#")[1]), destinationId);
  assert.match(
    source,
    /href="https:\/\/example.test\/#%E4%B8%AD%E6%96%87%E6%A0%87%E9%A2%98"/,
  );
});

test("generated heading IDs cannot shadow platform names and raw HTML remains disabled", () => {
  const html = renderToStaticMarkup(
    <Markdown
      source={
        '# location\n\n# __proto__\n\n# constructor\n\n<div id="location">raw</div>\n\n[heading](#location)'
      }
    />,
  );
  assert.doesNotMatch(html, /id="(?:location|__proto__|constructor)"|<div id=/);
  assert.match(html, /id="poketto-heading-location"/);
  assert.match(html, /href="#poketto-heading-location"/);
});

test("GFM generated footnote targets keep their own namespace", () => {
  const html = renderToStaticMarkup(
    <Markdown source={"A note[^1].\n\n[^1]: Footnote."} />,
  );
  assert.match(html, /href="#user-content-fn-1"/);
  assert.match(html, /id="user-content-fnref-1"/);
  assert.match(html, /href="#user-content-fnref-1"/);
  assert.doesNotMatch(html, /href="#poketto-heading-user-content-fn/);
});
