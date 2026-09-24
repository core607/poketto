import type { Root, RootContent } from "hast";

/** Heading anchors carry this prefix, so authored IDs cannot collide with page elements. */
export const HEADING_PREFIX = "poketto-heading-";

/** Run after heading IDs are assigned so authored fragments keep their target. */
export function readingHeading({ title }: { title?: string }) {
  return (tree: Root) => {
    if (!title) return;
    const first = tree.children.find(
      (node) => node.type !== "text" || node.value.trim(),
    );
    if (first?.type !== "element" || first.tagName !== "h1") return;
    if (visibleText(first).trim() !== title.trim()) return;
    first.tagName = "span";
    first.properties.ariaHidden = "true";
    first.children = [];
  };
}

function visibleText(node: RootContent): string {
  if (node.type === "text") return node.value;
  if (node.type !== "element") return "";
  if (node.tagName === "img") return String(node.properties.alt ?? "");
  return node.children.map(visibleText).join("");
}
