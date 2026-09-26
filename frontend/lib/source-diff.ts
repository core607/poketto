export type DiffLine = { kind: "same" | "removed" | "added"; text: string };
export type SourceDifference =
  | { kind: "unchanged" }
  | { kind: "side-by-side" }
  | { kind: "lines"; lines: DiffLine[] };

/** Keep exact line endings; expensive comparisons fall back to bounded source views. */
export function sourceDifference(
  before: string,
  after: string,
): SourceDifference {
  if (before === after) return { kind: "unchanged" };
  const encoder = new TextEncoder();
  if (encoder.encode(before).length + encoder.encode(after).length > 256 * 1024)
    return { kind: "side-by-side" };
  const left = before.match(/[^\n]*\n|[^\n]+$/g) ?? [];
  const right = after.match(/[^\n]*\n|[^\n]+$/g) ?? [];
  if (left.length + right.length > 2000 || left.length * right.length > 250_000)
    return { kind: "side-by-side" };
  const width = right.length + 1;
  const lengths = new Uint16Array((left.length + 1) * width);
  for (let i = left.length - 1; i >= 0; i--)
    for (let j = right.length - 1; j >= 0; j--)
      lengths[i * width + j] =
        left[i] === right[j]
          ? lengths[(i + 1) * width + j + 1] + 1
          : Math.max(lengths[(i + 1) * width + j], lengths[i * width + j + 1]);
  const lines: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < left.length || j < right.length) {
    if (i < left.length && j < right.length && left[i] === right[j]) {
      lines.push({ kind: "same", text: left[i++] });
      j++;
    } else if (
      i < left.length &&
      (j === right.length ||
        lengths[(i + 1) * width + j] >= lengths[i * width + j + 1])
    ) {
      lines.push({ kind: "removed", text: left[i++] });
    } else {
      lines.push({ kind: "added", text: right[j++] });
    }
  }
  return { kind: "lines", lines };
}
