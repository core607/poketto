import assert from "node:assert/strict";
import { createRequire } from "node:module";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import { Markdown } from "../components/markdown";
import { readingGuide } from "../lib/reading";

const render = (source: string) =>
  renderToStaticMarkup(<Markdown source={source} />);

test("math needs doubled dollars, so prices stay text", () => {
  const inline = render(String.raw`面积是 $$\pi r^2$$。`);
  assert.match(inline, /class="katex"/);
  assert.doesNotMatch(inline, /katex-display/);

  const block = render(["$$", String.raw`\sum_{i=1}^{n} i`, "$$"].join("\n"));
  assert.match(block, /class="katex-display"/);

  const fenced = render(["```math", String.raw`\frac{a}{b}`, "```"].join("\n"));
  assert.match(fenced, /class="katex-display"/);

  const prices = render("Pro 送 $100，Max 送 $250。");
  assert.doesNotMatch(prices, /katex/);
  assert.match(prices, /Pro 送 \$100，Max 送 \$250。/);
});

test("the KaTeX stylesheet comes from the same KaTeX that renders the markup", () => {
  const renderer = createRequire(require.resolve("rehype-katex"));
  assert.equal(
    renderer("katex/package.json").version,
    createRequire(import.meta.url)("katex/package.json").version,
  );
});

test("broken or unsafe math renders as an error and never as a link", () => {
  const broken = render(String.raw`$$\frac{1$$`);
  assert.match(broken, /katex-error/);

  const unsafe = render(String.raw`$$\href{javascript:alert(1)}{点我}$$`);
  assert.doesNotMatch(unsafe, /href="javascript/);
  assert.doesNotMatch(unsafe, /<a /);
});

test("a Mermaid block keeps its source until the browser draws it", () => {
  const html = render(["```mermaid", "graph TD", "  A-->B", "```"].join("\n"));
  assert.match(
    html,
    /<pre class="mermaid-source"><code>graph TD\n {2}A--&gt;B\n<\/code><\/pre>/,
  );
  assert.doesNotMatch(html, /hljs/);
});

test("contents anchors still match headings that contain math", () => {
  const source = ["## 公式 $$x^2$$", "## 第二节", "## 第三节"].join("\n\n");
  const { contents } = readingGuide(source);
  const html = render(source);
  assert.equal(contents.length, 3);
  for (const { id } of contents) assert.match(html, new RegExp(`id="${id}"`));
});
