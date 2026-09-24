import type { ContentsEntry } from "../lib/reading";

/** An article's headings as in-page links; the reading page decides when it is worth showing. */
export function TableOfContents({ entries }: { entries: ContentsEntry[] }) {
  return (
    <ol className="toc-list">
      {entries.map((entry) => (
        <li key={entry.id} className={entry.level ? "toc-sub" : undefined}>
          <a href={"#" + encodeURIComponent(entry.id)}>{entry.text}</a>
        </li>
      ))}
    </ol>
  );
}
