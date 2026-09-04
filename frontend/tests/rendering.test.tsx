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
  assert.match(html, /<h1>标题<\/h1>/);
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
