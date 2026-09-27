import type { DiffLine } from "../lib/source-diff";

const markers = { removed: "− ", added: "+ ", same: "  " };

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
