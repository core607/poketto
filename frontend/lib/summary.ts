/**
 * A plain-text summary of an article body for search results and link previews. Headings,
 * images, their italic captions, code and Markdown syntax are left out, and the result is cut
 * at a sentence boundary near `limit` characters when one is close enough.
 */
export function plainSummary(markdown: string, title = "", limit = 120) {
  const paragraphs = markdown
    .replace(/^---\n[\s\S]*?\n---\n/, "")
    .replace(/```[\s\S]*?```/g, "\n")
    .split(/\n\s*\n/);
  const kept: string[] = [];
  let afterImage = false;
  for (const raw of paragraphs) {
    const paragraph = raw.trim();
    if (!paragraph) continue;
    const withoutImages = paragraph.replace(/!\[[^\]]*\]\([^)]*\)/g, "").trim();
    // A paragraph that only italicises a line under an image is its caption, not prose.
    const caption = afterImage && /^([*_]).+\1$/s.test(withoutImages);
    afterImage = withoutImages.length === 0;
    // Headings name the article or its sections; the summary is the prose under them.
    if (afterImage || caption || /^#{1,6}\s/.test(withoutImages)) continue;
    const text = withoutImages
      .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")
      .replace(/<[^>]+>/g, "")
      .replace(/^\s{0,3}(#{1,6}|>|[-*+]|\d+\.)\s+/gm, "")
      .replace(/[*_~`]+/g, "")
      .replace(/\s+/g, " ")
      .trim();
    if (!text || text === title.trim()) continue;
    kept.push(text);
    if (kept.join(" ").length >= limit) break;
  }
  const joined = kept.join(" ");
  if (joined.length <= limit) return joined;
  const cut = joined.slice(0, limit);
  const stop = Math.max(
    cut.lastIndexOf("。"),
    cut.lastIndexOf("；"),
    cut.lastIndexOf(". "),
  );
  return (
    stop > limit / 2 ? cut.slice(0, stop + 1) : cut.trimEnd() + "…"
  ).trim();
}
