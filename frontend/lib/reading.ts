import type { Root, RootContent } from "hast";
import { toString } from "hast-util-to-string";
import remarkParse from "remark-parse";
import remarkRehype from "remark-rehype";
import { unified } from "unified";
import {
  HEADING_PREFIX,
  headingAnchors,
  markdownSyntax,
  titleHeading,
} from "./reading-heading";

/** One table-of-contents line; `level` is 0 for the top listed level and 1 below it. */
export type ContentsEntry = { id: string; text: string; level: number };

// react-markdown parses the same way before running the renderer's plugins.
const pipeline = unified()
  .use(remarkParse)
  .use(markdownSyntax)
  .use(remarkRehype, { allowDangerousHtml: true })
  .use(headingAnchors);

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
  const hidden = titleHeading(tree, title);
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
          node !== hidden
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

/** About 400 CJK characters, or 200 other words, a minute; never less than one. */
export function readingMinutes(text: string) {
  const characters = text.match(CJK)?.length ?? 0;
  const words = text.replace(CJK, " ").match(WORD)?.length ?? 0;
  return Math.max(1, Math.round(characters / 400 + words / 200));
}
