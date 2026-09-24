import type { Element, Root, RootContent } from "hast";
import { normalizeUri } from "micromark-util-sanitize-uri";
import rehypeSlug from "rehype-slug";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import type { PluggableList } from "unified";

/** Heading anchors carry this prefix, so authored IDs cannot collide with page elements. */
export const HEADING_PREFIX = "poketto-heading-";

/**
 * The syntax and anchor steps shared by the renderer and the reading guide. Both must run exactly
 * these, in this order, for a listed heading to find its anchor on the page.
 */
// Math needs doubled dollars even inline, so prices such as "$100 and $250" stay text.
export const markdownSyntax: PluggableList = [
  remarkGfm,
  [remarkMath, { singleDollarTextMath: false }],
];
export const headingAnchors: PluggableList = [
  [rehypeSlug, { prefix: HEADING_PREFIX }],
];

/** The in-page link to a heading anchor. */
export function headingHref(id: string) {
  return "#" + normalizeUri(id);
}

/** The opening h1 when its visible text only repeats the page title. */
export function titleHeading(tree: Root, title?: string) {
  if (!title) return undefined;
  const first = tree.children.find(
    (node) => node.type !== "text" || node.value.trim(),
  );
  if (first?.type !== "element" || first.tagName !== "h1") return undefined;
  return visibleText(first).trim() === title.trim()
    ? (first as Element)
    : undefined;
}

/** Run after heading IDs are assigned so authored fragments keep their target. */
export function readingHeading({ title }: { title?: string }) {
  return (tree: Root) => {
    const heading = titleHeading(tree, title);
    if (!heading) return;
    heading.tagName = "span";
    heading.properties.ariaHidden = "true";
    heading.children = [];
  };
}

function visibleText(node: RootContent): string {
  if (node.type === "text") return node.value;
  if (node.type !== "element") return "";
  if (node.tagName === "img") return String(node.properties.alt ?? "");
  return node.children.map(visibleText).join("");
}
