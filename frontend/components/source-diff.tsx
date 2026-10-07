import type { DiffLine } from "../lib/source-diff";

const markers = { removed: "− ", added: "+ ", same: "  " };

/** Shows each line ending, so CRLF and a missing final newline are visible differences. */
export function lineEnding(text: string) {
  if (text.endsWith("\r\n")) return text.slice(0, -2) + " ⟪CRLF⟫";
  if (text.endsWith("\n")) return text.slice(0, -1);
  return text + " ⟪无行尾换行⟫";
}

/** Line-by-line source difference; format decides how each line's ending is shown. */
export function SourceDiff({
  lines,
  format = (text) => text.replace(/\r?\n$/, ""),
}: {
  lines: DiffLine[];
  format?: (text: string) => string;
}) {
  return (
    <pre className="history-diff" aria-label="正文差异">
      {lines.map((line, index) => (
        <span key={index} className={"history-line " + line.kind}>
          <span aria-hidden="true">{markers[line.kind]}</span>
          {format(line.text)}
        </span>
      ))}
    </pre>
  );
}
