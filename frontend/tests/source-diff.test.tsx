import assert from "node:assert/strict";
import test from "node:test";
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
