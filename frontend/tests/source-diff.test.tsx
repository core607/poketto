import assert from "node:assert/strict";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import { SourceDiff, lineEnding } from "../components/source-diff";
import { sourceDifference } from "../lib/source-diff";

test("source differences retain exact before and after bytes including line endings", () => {
  for (const [before, after] of [
    ["first\nold\nlast", "first\nnew\nlast\n"],
    ["", "new\n"],
    ["deleted", ""],
    ["same\r\n", "same\n"],
    ["a\na\nb\n", "a\nb\na\n"],
    ["<script>bad()</script>\n猫", "猫\n<em>plain</em>"],
  ]) {
    const difference = sourceDifference(before, after);
    assert.equal(difference.kind, "lines");
    if (difference.kind !== "lines")
      throw new Error("line difference required");
    assert.equal(
      difference.lines
        .filter((line) => line.kind !== "added")
        .map((line) => line.text)
        .join(""),
      before,
    );
    assert.equal(
      difference.lines
        .filter((line) => line.kind !== "removed")
        .map((line) => line.text)
        .join(""),
      after,
    );
  }
});

test("the history diff marks CRLF and a missing final newline but not LF", () => {
  const rendered = (before: string, after: string) => {
    const difference = sourceDifference(before, after);
    if (difference.kind !== "lines")
      throw new Error("line difference required");
    const html = renderToStaticMarkup(
      <SourceDiff lines={difference.lines} format={lineEnding} />,
    );
    return [
      ...html.matchAll(
        /class="history-line (\w+)"><span aria-hidden="true">[^<]*<\/span>([^<]*)</g,
      ),
    ].map(([, kind, text]) => [kind, text]);
  };
  assert.deepEqual(rendered("a\r\nb\r\n", "a\nb\n"), [
    ["removed", "a ⟪CRLF⟫"],
    ["removed", "b ⟪CRLF⟫"],
    ["added", "a"],
    ["added", "b"],
  ]);
  assert.deepEqual(
    rendered("same\r\nlf\nedit\r\nlast", "same\r\nlf\nedited\nlast\n"),
    [
      ["same", "same ⟪CRLF⟫"],
      ["same", "lf"],
      ["removed", "edit ⟪CRLF⟫"],
      ["removed", "last ⟪无行尾换行⟫"],
      ["added", "edited"],
      ["added", "last"],
    ],
  );
});

test("comparison work and UTF-8 size have a side-by-side fallback", () => {
  assert.deepEqual(sourceDifference("same", "same"), { kind: "unchanged" });
  assert.deepEqual(sourceDifference("猫".repeat(90_000), "small"), {
    kind: "side-by-side",
  });
  assert.deepEqual(sourceDifference("a\n".repeat(501), "b\n".repeat(501)), {
    kind: "side-by-side",
  });
  assert.deepEqual(sourceDifference("a\n".repeat(2001), ""), {
    kind: "side-by-side",
  });
});
