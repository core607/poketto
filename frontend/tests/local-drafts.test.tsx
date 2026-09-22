import assert from "node:assert/strict";
import test from "node:test";
import {
  clearAccountDrafts,
  draftGeneration,
  localDrafts,
  recoveredFile,
  retainDraft,
  type DraftStorage,
  type LocalDraft,
} from "../lib/local-drafts";
import type { RepositoryFile } from "../lib/types";

function storage(): DraftStorage {
  const values = new Map<string, string>();
  return {
    get length() {
      return values.size;
    },
    key: (index) => [...values.keys()][index] ?? null,
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => {
      values.set(key, value);
    },
    removeItem: (key) => {
      values.delete(key);
    },
  };
}
function draft(id = "first", source = "Unsaved text"): LocalDraft {
  return {
    version: 1,
    id,
    accountId: "author",
    workspaceId: "space",
    path: "private/note.md",
    source,
    updatedAt: 1,
    baseline: {
      path: "private/note.md",
      commit: "old-commit",
      revision: "old-revision",
      expectedAbsence: false,
    },
  };
}
const file: RepositoryFile = {
  path: "private/note.md",
  source: "Saved text",
  commit: "current-commit",
  revision: "old-revision",
  expectedAbsence: false,
  publicScope: false,
  publicPage: null,
  diagnostics: [],
};

test("separate tab drafts survive each other's writes and account logout clears only that account", () => {
  const store = storage();
  retainDraft(store, draft("first", "first tab"));
  retainDraft(store, draft("second", "second tab"));
  retainDraft(store, { ...draft(), accountId: "someone-else" });
  retainDraft(store, { ...draft(), workspaceId: "other-space" });
  assert.deepEqual(
    localDrafts(store, "author", "space").map((item) => item.source),
    ["first tab", "second tab"],
  );
  assert.equal(localDrafts(store, "nobody", "space").length, 0);
  clearAccountDrafts(store, "author");
  assert.equal(localDrafts(store, "author", "space").length, 0);
  assert.equal(
    localDrafts(store, "someone-else", "space")[0].source,
    "Unsaved text",
  );
});

test("logout fences an already-open tab while a freshly authenticated editor can retain new work", () => {
  const store = storage();
  const original = draftGeneration(store, "author");
  retainDraft(store, draft(), original);
  clearAccountDrafts(store, "author");
  assert.throws(() => retainDraft(store, draft(), original), /退出/);
  assert.equal(localDrafts(store, "author", "space").length, 0);
  retainDraft(store, draft("fresh"), draftGeneration(store, "author"));
  assert.equal(localDrafts(store, "author", "space")[0].id, "fresh");
});

test("capacity refusal preserves all existing recovery records, including updates at the count limit", () => {
  const store = storage();
  for (let index = 0; index < 20; index++)
    retainDraft(store, draft(`draft-${index}`));
  assert.throws(() => retainDraft(store, draft("overflow")), /数量/);
  retainDraft(store, draft("draft-0", "continued editing"));
  assert.equal(store.length, 20);
  assert.ok(
    localDrafts(store, "author", "space").some(
      (item) => item.source === "continued editing",
    ),
  );
  const bounded = storage();
  retainDraft(bounded, draft("large-a", "a".repeat(900_000)));
  retainDraft(bounded, draft("large-b", "b".repeat(900_000)));
  assert.throws(
    () => retainDraft(bounded, draft("overflow", "c".repeat(400_000))),
    /空间已满/,
  );
  assert.equal(bounded.length, 2);
  assert.throws(
    () => retainDraft(bounded, draft("multibyte", "😸".repeat(300_000))),
    /大小/,
  );
  assert.equal(bounded.length, 2);
});

test("recovery preserves stale conflict preconditions but can use a fresh commit when the file is unchanged", () => {
  const cached = draft();
  assert.equal(recoveredFile(file, cached), file);
  const changed = {
    ...file,
    revision: "someone-elses-revision",
    source: "Someone else's edit",
  };
  const restored = recoveredFile(changed, cached);
  assert.equal(restored.commit, "old-commit");
  assert.equal(restored.revision, "old-revision");
  assert.equal(restored.source, "Someone else's edit");
  assert.equal(restored.publicScope, changed.publicScope);
  const absent = {
    ...cached,
    baseline: { ...cached.baseline, revision: null, expectedAbsence: true },
  };
  assert.equal(recoveredFile(changed, absent).expectedAbsence, true);
});

test("corrupt or mis-scoped storage never supplies recovery text and write failures do not delete prior work", () => {
  const store = storage();
  retainDraft(store, draft());
  const name = store.key(0)!;
  store.setItem(
    name,
    JSON.stringify({ ...draft(), accountId: "forged-owner" }),
  );
  assert.equal(localDrafts(store, "forged-owner", "space").length, 0);
  store.setItem(name, "not json");
  assert.equal(localDrafts(store, "author", "space").length, 0);
  retainDraft(store, draft());
  store.setItem = () => {
    throw new Error("browser quota");
  };
  assert.throws(() => retainDraft(store, draft("second")), /browser quota/);
  assert.equal(localDrafts(store, "author", "space")[0].source, "Unsaved text");
});
