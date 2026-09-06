import assert from "node:assert/strict";
import test from "node:test";
import { saveRepositoryFile } from "../lib/repository-write";
import type { RepositoryFile } from "../lib/types";

test("renaming an unsaved draft creates only its final path", async () => {
  const previous = globalThis.fetch;
  let submitted: unknown;
  globalThis.fetch = async (input, options) => {
    if (String(input).endsWith("/csrf"))
      return Response.json({ headerName: "X-CSRF-TOKEN", token: "fixture" });
    if (String(input).includes("/file?"))
      return Response.json({ expectedAbsence: true });
    submitted = JSON.parse(String(options?.body));
    return Response.json({ committed: true, commit: "after" });
  };
  try {
    const file: RepositoryFile = {
      path: "untitled.md",
      expectedAbsence: true,
      revision: null,
      commit: "before",
      source: null,
      diagnostics: [],
    };
    await saveRepositoryFile(file, "中文/新名字.md", "# 保留草稿", false);
    assert.deepEqual(submitted, {
      baseCommit: "before",
      changes: [
        {
          path: "中文/新名字.md",
          expectedAbsence: true,
          expectedRevision: null,
          content: "# 保留草稿",
        },
      ],
    });
  } finally {
    globalThis.fetch = previous;
  }
});

test("an acknowledged unchanged save is returned without a retry or an uncertain error", async () => {
  const previous = globalThis.fetch;
  let writes = 0;
  globalThis.fetch = async (input) => {
    if (String(input).endsWith("/csrf"))
      return Response.json({ headerName: "X-CSRF-TOKEN", token: "fixture" });
    writes++;
    return Response.json({
      committed: false,
      commit: "same",
      snapshotUpdated: false,
      revisions: { "a.md": "revision" },
    });
  };
  try {
    const result = await saveRepositoryFile(
      {
        path: "a.md",
        expectedAbsence: false,
        revision: "revision",
        commit: "same",
        source: "# Same",
        diagnostics: [],
      },
      "a.md",
      "# Same",
      false,
    );
    assert.equal(result.committed, false);
    assert.equal(result.commit, "same");
    assert.equal(writes, 1);
  } finally {
    globalThis.fetch = previous;
  }
});
