import { test } from "node:test";
import assert from "node:assert/strict";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { randomUUID } from "node:crypto";
import { Data } from "../src/data.js";
import { config } from "../src/config.js";

test("missing or immediately exiting corpus helpers reject requests without crashing the service", async () => {
  for (const python of [
    join(tmpdir(), randomUUID(), "missing-python"),
    process.execPath,
  ]) {
    const data = new Data({ ...config(), python });
    try {
      // Node rejects the Python-only -u flag, closing the pipe while a large request is written.
      await assert.rejects(
        data.call({ op: "query", text: "x".repeat(8_000_000) }),
        /Corpus helper/,
      );
      await assert.rejects(data.call({ op: "query" }), /Corpus helper/);
    } finally {
      data.stop();
    }
  }
});
