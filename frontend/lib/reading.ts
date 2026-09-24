import type { Element, Root, RootContent } from "hast";
import { toString } from "hast-util-to-string";
import rehypeSlug from "rehype-slug";
import remarkGfm from "remark-gfm";
import remarkParse from "remark-parse";
import remarkRehype from "remark-rehype";
import { unified } from "unified";
import { HEADING_PREFIX } from "./reading-heading";

/** One table-of-contents line; `level` is 0 for the top listed level and 1 below it. */
export type ContentsEntry = { id: string; text: string; level: number };

// The same parse and slug steps as components/markdown.tsx, so every anchor matches the page.
const pipeline = unified()
  .use(remarkParse)
  .use(remarkGfm)
  .use(remarkRehype, { allowDangerousHtml: true })
  .use(rehypeSlug, { prefix: HEADING_PREFIX });

const CJK =
  /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}]/gu;
const WORD = /[\p{L}\p{N}]+(?:['’-][\p{L}\p{N}]+)*/gu;

/**
 * An article's estimated reading minutes and the headings its table of contents lists. The
 * first heading is skipped when it only repeats the page title, as the page hides it. Only the
 * two shallowest heading levels in use, down to h3, are listed.
 */
export function readingGuide(source: string, title?: string) {
  const tree = pipeline.runSync(pipeline.parse(source)) as Root;
  const headings: { id: string; text: string; depth: number }[] = [];
  const first = tree.children.find(
    (node) => node.type !== "text" || node.value.trim(),
  );
  const visit = (nodes: (Root | RootContent)[]) => {
    for (const node of nodes) {
      if (node.type !== "element" && node.type !== "root") continue;
      if (node.type === "element") {
        const depth = /^h([1-3])$/.exec(node.tagName)?.[1];
        const id = node.properties.id;
        if (
          depth &&
          typeof id === "string" &&
          id.startsWith(HEADING_PREFIX) &&
          !(node === first && repeatsTitle(node, title))
        ) {
          const text = toString(node).trim();
          if (text) headings.push({ id, text, depth: Number(depth) });
          continue;
        }
      }
      visit(node.children);
    }
  };
  visit([tree]);
  const top = Math.min(...headings.map((heading) => heading.depth));
  const contents: ContentsEntry[] = headings
    .filter((heading) => heading.depth <= top + 1)
    .map(({ id, text, depth }) => ({ id, text, level: depth - top }));
  return { minutes: readingMinutes(toString(tree)), contents };
}

function repeatsTitle(node: Element, title?: string) {
  return (
    node.tagName === "h1" &&
    title !== undefined &&
    toString(node).trim() === title.trim()
  );
}

/** About 400 CJK characters, or 200 other words, a minute; never less than one. */
export function readingMinutes(text: string) {
  const characters = text.match(CJK)?.length ?? 0;
  const words = text.replace(CJK, " ").match(WORD)?.length ?? 0;
  return Math.max(1, Math.round(characters / 400 + words / 200));
}
