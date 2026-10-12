import { hue } from "../components/ui/icons";

/** What a discovery card without an image shows on its typeset cover. */
export type CoverFace = {
  /** Warm hue shared by every card of one space. */
  hue: number;
  spaceName: string;
  /** The snippet's opening sentence; empty selects the tag-and-seal layout. */
  lead: string;
  /** Creative writing sets its lead vertically. */
  vertical: boolean;
  /** The primary tag set beside the seal; without one the seal stands alone. */
  label: string;
  seal: string;
};

const WARM_HUES = [6, 14, 22, 28, 34, 40];
// Longer opening sentences no longer fit the cover at its smallest width.
const MAX_LEAD = 80;
const LEAD_PREFIX = /^(?:TL;?DR|太长不看版?)\s*[:：]\s*/i;
const OPENING = "（(「『“‘《【[";
const CLOSING = "）)」』”’》】]";
const ENDINGS = "。！？!?…";
const CREATIVE = new Set([
  "小说",
  "短篇",
  "诗",
  "诗歌",
  "现代诗",
  "散文",
  "随笔",
  "fiction",
  "poem",
  "poetry",
  "essay",
]);

/**
 * The snippet's opening sentence and the text after it. A label such as "TL;DR：" belongs to the
 * article's layout, not to the sentence, and terminators inside brackets or quotes do not end it.
 */
export function splitLead(snippet: string) {
  const text = snippet.replace(LEAD_PREFIX, "");
  let depth = 0;
  for (let index = 0; index < Math.min(text.length, MAX_LEAD); index++) {
    const character = text[index];
    if (OPENING.includes(character)) depth++;
    else if (CLOSING.includes(character)) depth = Math.max(0, depth - 1);
    else if (
      depth === 0 &&
      (ENDINGS.includes(character) ||
        (character === "." && /\s/.test(text[index + 1] ?? " ")))
    ) {
      let end = index + 1;
      while (
        end < text.length &&
        (ENDINGS.includes(text[end]) || CLOSING.includes(text[end]))
      )
        end++;
      return { lead: text.slice(0, end).trim(), rest: text.slice(end).trim() };
    }
  }
  return { lead: "", rest: snippet };
}

export function coverFace({
  space,
  spaceName,
  tags,
  lead,
}: {
  space: string;
  spaceName: string;
  tags: string[];
  lead: string;
}): CoverFace {
  return {
    hue: WARM_HUES[hue(space) % WARM_HUES.length],
    spaceName,
    lead,
    vertical: tags.some((tag) => CREATIVE.has(tag.toLowerCase())),
    label: tags[0] ?? "",
    seal: [...(spaceName.trim() || "?")][0].toUpperCase(),
  };
}
