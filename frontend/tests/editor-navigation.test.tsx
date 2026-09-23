import assert from "node:assert/strict";
import test from "node:test";
import { Window } from "happy-dom";
import { scopedRoot, workspaceId } from "./workspace-fixture";
import { localDrafts, retainDraft, type LocalDraft } from "../lib/local-drafts";
import {
  navigationFolder,
  privateCreationPath,
  type ContentLocation,
} from "../lib/repository-navigation";

type RequestHandler = (url: URL, options?: RequestInit) => Promise<Response>;
type Control = {
  value: string;
  dispatchEvent: (event: unknown) => boolean;
};
type Clickable = { click: () => void };
type FormControl = Control & {
  closest: (selector: string) => Control | null;
};

const owner = {
  accountId: "owner",
  workspaceId,
  role: "OWNER" as const,
  capabilities: ["READ_PRIVATE", "WRITE_PRIVATE", "PUBLISH"],
};

function json(value: unknown) {
  return Response.json(value);
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

async function mountEditor(
  url: string,
  handler: RequestHandler,
  onNavigate: (location: ContentLocation, replace?: boolean) => void,
  prepare?: (window: Window) => void,
) {
  const window = new Window({ url });
  Object.defineProperty(window.navigator, "locks", {
    configurable: true,
    value: {
      request: async (_name: string, operation: () => unknown) => operation(),
    },
  });
  prepare?.(window);
  const globals = {
    window,
    document: window.document,
    navigator: window.navigator,
    HTMLElement: window.HTMLElement,
    HTMLDialogElement: window.HTMLDialogElement,
    HTMLInputElement: window.HTMLInputElement,
    HTMLTextAreaElement: window.HTMLTextAreaElement,
    FormData: window.FormData,
    Event: window.Event,
    IS_REACT_ACT_ENVIRONMENT: true,
    requestAnimationFrame: window.requestAnimationFrame.bind(window),
    cancelAnimationFrame: window.cancelAnimationFrame.bind(window),
  };
  const previous = new Map(
    Object.keys(globals).map((name) => [
      name,
      Object.getOwnPropertyDescriptor(globalThis, name),
    ]),
  );
  for (const [name, value] of Object.entries(globals))
    Object.defineProperty(globalThis, name, {
      configurable: true,
      writable: true,
      value,
    });
  const previousFetch = globalThis.fetch;
  globalThis.fetch = (input, options) =>
    handler(new URL(String(input), "http://localhost"), options);
  const { act } = await import("react");
  const { createRoot } = await import("react-dom/client");
  const { Editor } = await import("../components/editor");
  const { ConfirmationProvider } = await import("../components/confirmation");
  const container = window.document.createElement("div");
  window.document.body.append(container);
  const root = scopedRoot(createRoot(container as unknown as HTMLDivElement));
  let mounted = true;
  await act(async () =>
    root.render(
      <ConfirmationProvider>
        <Editor
          identity={owner}
          onDirtyChange={() => {}}
          onNavigate={onNavigate}
        />
      </ConfirmationProvider>,
    ),
  );
  return {
    window,
    container,
    act,
    async cleanup() {
      if (mounted) {
        await act(async () => root.unmount());
        mounted = false;
      }
      globalThis.fetch = previousFetch;
      await window.happyDOM.close();
      for (const [name, descriptor] of previous) {
        if (descriptor) Object.defineProperty(globalThis, name, descriptor);
        else Reflect.deleteProperty(globalThis, name);
      }
    },
    async unmount() {
      if (!mounted) return;
      await act(async () => root.unmount());
      mounted = false;
    },
  };
}

async function settle(act: (callback: () => unknown) => Promise<unknown>) {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

function directoryResponse(
  path: string,
  entries: { path: string; kind: "FILE" | "DIRECTORY" }[],
  expectedAbsence = false,
  commit = "before",
) {
  return json({
    commit,
    path,
    expectedAbsence,
    entries,
    nextOffset: null,
  });
}

test("folder navigation preserves a dirty draft and reports folder separately from path", async (t) => {
  const navigations: { location: ContentLocation; replace?: boolean }[] = [];
  const editor = await mountEditor(
    "http://localhost/admin?path=public%2Fnotes%2Fexisting.md&folder=public%2Fnotes",
    async (url) => {
      if (url.pathname === "/api/auth/csrf")
        return json({ headerName: "X-CSRF", token: "fixture" });
      if (url.pathname.endsWith("/repository/tree"))
        return json({ commit: "before", entries: [], diagnostics: [] });
      if (url.pathname.endsWith("/repository/directory")) {
        const path = url.searchParams.get("path") ?? "";
        return path === ""
          ? directoryResponse(path, [
              { path: "public", kind: "DIRECTORY" },
              { path: "private", kind: "DIRECTORY" },
            ])
          : path === "public"
            ? directoryResponse(path, [
                { path: "public/notes", kind: "DIRECTORY" },
              ])
            : directoryResponse(path, [
                { path: "public/notes/existing.md", kind: "FILE" },
              ]);
      }
      if (url.pathname.endsWith("/repository/file"))
        return json({
          path: "public/notes/existing.md",
          source: "# Existing",
          revision: "revision",
          commit: "before",
          expectedAbsence: false,
          publicScope: true,
          diagnostics: [],
        });
      if (url.pathname.endsWith("/repository/preview"))
        return json({
          body: "# Existing",
          images: {},
          galleryStatus: "COMPLETE",
        });
      throw new Error(`Unexpected API call: ${url.pathname}`);
    },
    (location, replace) => navigations.push({ location, replace }),
  );
  t.after(() => editor.cleanup());
  await settle(editor.act);
  navigations.length = 0;
  const textarea = editor.container.querySelector("textarea") as Control | null;
  assert.ok(textarea);
  Object.getOwnPropertyDescriptor(
    editor.window.HTMLTextAreaElement.prototype,
    "value",
  )!.set!.call(textarea, "# Unsaved draft");
  await editor.act(async () =>
    textarea.dispatchEvent(new editor.window.Event("input", { bubbles: true })),
  );
  const publicSummary = [...editor.container.querySelectorAll("summary")].find(
    (item) => item.textContent?.trim() === "已发布",
  );
  assert.ok(publicSummary);
  await editor.act(async () => publicSummary.click());
  assert.equal(textarea.value, "# Unsaved draft");
  assert.deepEqual(navigations, [
    {
      location: {
        folder: "public",
        path: "public/notes/existing.md",
      },
      replace: false,
    },
  ]);
  assert.equal(editor.container.querySelector("dialog[open]"), null);
});

test("creation paths keep the selected category under private content", () => {
  assert.equal(navigationFolder("public/notes/"), "public/notes");
  assert.equal(
    privateCreationPath("public/notes", "new note", "note"),
    "private/notes/new note.md",
  );
  assert.equal(
    privateCreationPath("public/notes", "travel", "folder"),
    "private/notes/travel/index.md",
  );
  assert.equal(
    privateCreationPath("private/notes", "already.md", "note"),
    "private/notes/already.md",
  );
  assert.throws(
    () => privateCreationPath("public/notes", "../escape", "note"),
    /路径分隔符或控制字符/,
  );
});

test("creation prepares private folder drafts, saves index.md explicitly, and rejects duplicates", async (t) => {
  const navigations: ContentLocation[] = [];
  const patches: { path: string; body: string }[] = [];
  const editor = await mountEditor(
    "http://localhost/admin?tab=content&folder=public%2Fnotes",
    async (url, options) => {
      if (url.pathname === "/api/auth/csrf")
        return json({ headerName: "X-CSRF", token: "fixture" });
      if (url.pathname.endsWith("/repository/tree"))
        return json({ commit: "before", entries: [], diagnostics: [] });
      if (url.pathname.endsWith("/repository/directory")) {
        const path = url.searchParams.get("path") ?? "";
        return directoryResponse(path, [], path === "private/notes/travel");
      }
      if (url.pathname.endsWith("/repository/file")) {
        const path = url.searchParams.get("path");
        if (path?.endsWith("/existing.md"))
          return json({
            path,
            source: "# Existing",
            revision: "revision",
            commit: "before",
            expectedAbsence: false,
            publicScope: false,
            diagnostics: [],
          });
        return json({
          path,
          source: null,
          revision: null,
          commit: "before",
          expectedAbsence: true,
          publicScope: false,
          diagnostics: [],
        });
      }
      if (url.pathname.endsWith("/repository/preview"))
        return json({ body: "", images: {}, galleryStatus: "COMPLETE" });
      if (url.pathname.endsWith("/repository/patch")) {
        const body = JSON.parse(String(options?.body)) as {
          changes: { path: string; content: string }[];
        };
        patches.push({
          path: body.changes[0].path,
          body: body.changes[0].content,
        });
        return json({
          commit: "after",
          committed: true,
          snapshotUpdated: false,
          revisions: { [body.changes[0].path]: "after-revision" },
        });
      }
      throw new Error(`Unexpected API call: ${url.pathname}`);
    },
    (location) => navigations.push(location),
  );
  t.after(() => editor.cleanup());
  await settle(editor.act);
  const button = (label: string) => {
    const item = [...editor.container.querySelectorAll("button")].find(
      (candidate) => candidate.textContent?.trim() === label,
    );
    assert.ok(item, label);
    return item;
  };
  await editor.act(async () => button("新建笔记").click());
  assert.match(editor.container.textContent!, /默认创建在 private\/notes\//);
  await editor.act(async () => button("取消").click());
  await editor.act(async () => button("新建分类").click());
  const name = editor.container.querySelector(
    'input[name="name"]',
  ) as FormControl | null;
  assert.ok(name);
  Object.getOwnPropertyDescriptor(
    editor.window.HTMLInputElement.prototype,
    "value",
  )!.set!.call(name, "travel");
  await editor.act(async () =>
    name.dispatchEvent(new editor.window.Event("input", { bubbles: true })),
  );
  const creationForm = name.closest("form");
  assert.ok(creationForm);
  await editor.act(async () =>
    creationForm.dispatchEvent(
      new editor.window.Event("submit", { bubbles: true, cancelable: true }),
    ),
  );
  await settle(editor.act);
  assert.equal(patches.length, 0, "preparing a draft must not write");
  assert.match(
    editor.container.textContent!,
    /分类的介绍页已打开，保存后分类就建好了/,
  );
  assert.equal(
    (editor.container.querySelector(".path-label input") as Control | null)
      ?.value,
    "private/notes/travel/index.md",
  );
  const textarea = editor.container.querySelector("textarea") as Control | null;
  assert.ok(textarea);
  assert.match(textarea.value, /^---\nid: [0-9a-f-]{36}\n---\n$/);
  Object.getOwnPropertyDescriptor(
    editor.window.HTMLTextAreaElement.prototype,
    "value",
  )!.set!.call(textarea, "# Travel");
  await editor.act(async () =>
    textarea.dispatchEvent(new editor.window.Event("input", { bubbles: true })),
  );
  await editor.act(async () => button("保存").click());
  await settle(editor.act);
  assert.deepEqual(patches, [
    { path: "private/notes/travel/index.md", body: "# Travel" },
  ]);
  assert.match(editor.container.textContent!, /已保存/);
  const navigationCount = navigations.length;
  await editor.act(async () => button("新建笔记").click());
  const duplicateName = editor.container.querySelector(
    'input[name="name"]',
  ) as FormControl | null;
  assert.ok(duplicateName);
  Object.getOwnPropertyDescriptor(
    editor.window.HTMLInputElement.prototype,
    "value",
  )!.set!.call(duplicateName, "existing.md");
  await editor.act(async () =>
    duplicateName.dispatchEvent(
      new editor.window.Event("input", { bubbles: true }),
    ),
  );
  const duplicateForm = duplicateName.closest("form");
  assert.ok(duplicateForm);
  await editor.act(async () =>
    duplicateForm.dispatchEvent(
      new editor.window.Event("submit", { bubbles: true, cancelable: true }),
    ),
  );
  await settle(editor.act);
  assert.match(editor.container.textContent!, /同名文件已经存在/);
  assert.equal(patches.length, 1);
  assert.equal(navigations.length, navigationCount);
});

test("preparing an existing article identity changes only the draft and retains it on a failed save", async (t) => {
  const original = "# Existing\n\nUnsaved work";
  const prepared =
    "---\nid: 12345678-1234-4234-8234-123456789abc\n---\n" + original;
  const requests: string[] = [];
  const editor = await mountEditor(
    "http://localhost/admin?path=private%2Fnote.md",
    async (url, options) => {
      if (url.pathname === "/api/auth/csrf")
        return json({ headerName: "X-CSRF", token: "fixture" });
      if (url.pathname.endsWith("/repository/tree"))
        return json({ commit: "before", entries: [], diagnostics: [] });
      if (url.pathname.endsWith("/repository/directory"))
        return directoryResponse("", []);
      if (url.pathname.endsWith("/repository/file"))
        return json({
          path: "private/note.md",
          source: original,
          revision: "revision",
          commit: "before",
          expectedAbsence: false,
          publicScope: false,
          diagnostics: [],
        });
      if (url.pathname.endsWith("/repository/preview"))
        return json({ body: original, galleryStatus: "COMPLETE" });
      if (url.pathname.endsWith("/repository/article-identity")) {
        requests.push("prepare");
        assert.deepEqual(JSON.parse(String(options?.body)), {
          path: "private/note.md",
          source: original,
        });
        return json({
          source: prepared,
          articleId: "12345678-1234-4234-8234-123456789abc",
        });
      }
      if (url.pathname.endsWith("/repository/patch")) {
        requests.push("save");
        const patch = JSON.parse(String(options?.body));
        assert.equal(patch.baseCommit, "before");
        assert.equal(patch.changes[0].expectedRevision, "revision");
        assert.equal(patch.changes[0].content, prepared);
        return new Response("{}", {
          status: 409,
          headers: { "Content-Type": "application/json" },
        });
      }
      throw new Error(`Unexpected API call: ${url.pathname}`);
    },
    () => {},
  );
  t.after(() => editor.cleanup());
  await settle(editor.act);
  const button = (label: string) => {
    const found = [...editor.container.querySelectorAll("button")].find(
      (item) => item.textContent?.trim() === label,
    );
    assert.ok(found, label);
    return found;
  };
  await editor.act(async () => button("启用文章互动").click());
  await settle(editor.act);
  assert.deepEqual(requests, ["prepare"]);
  assert.equal(
    (editor.container.querySelector("textarea") as Control | null)?.value,
    prepared,
  );
  assert.match(editor.container.textContent!, /点击保存后写入仓库/);
  await editor.act(async () => button("保存").click());
  await settle(editor.act);
  assert.deepEqual(requests, ["prepare", "save"]);
  assert.equal(
    (editor.container.querySelector("textarea") as Control | null)?.value,
    prepared,
  );
  assert.match(editor.container.textContent!, /编辑框中的内容仍然保留/);
});

test("draft recovery rechecks access and preserves the original write preconditions after remote edits", async (t) => {
  let allowed = true;
  const patches: {
    baseCommit: string;
    changes: { expectedRevision: string; content: string }[];
  }[] = [];
  const cached: LocalDraft = {
    version: 1,
    id: "recovery",
    accountId: "owner",
    workspaceId,
    path: "private/note.md",
    source: "My unsaved draft",
    updatedAt: 1,
    baseline: {
      path: "private/note.md",
      commit: "old-commit",
      revision: "old-revision",
      expectedAbsence: false,
    },
  };
  const editor = await mountEditor(
    "http://localhost/admin?path=private%2Fnote.md",
    async (url, options) => {
      if (url.pathname === "/api/auth/csrf")
        return json({ headerName: "X-CSRF", token: "fixture" });
      if (url.pathname.endsWith("/repository/tree"))
        return json({ commit: "new-commit", entries: [], diagnostics: [] });
      if (url.pathname.endsWith("/repository/directory"))
        return directoryResponse("", []);
      if (url.pathname.endsWith("/repository/file"))
        return allowed
          ? json({
              path: cached.path,
              source: "Someone else's newer text",
              commit: "new-commit",
              revision: "new-revision",
              expectedAbsence: false,
              publicScope: false,
              diagnostics: [],
            })
          : new Response("{}", { status: 403 });
      if (url.pathname.endsWith("/repository/preview"))
        return json({ body: "preview", galleryStatus: "COMPLETE" });
      if (url.pathname.endsWith("/repository/patch")) {
        patches.push(JSON.parse(String(options?.body)));
        return new Response("{}", { status: 409 });
      }
      throw new Error(`Unexpected API call: ${url.pathname}`);
    },
    () => {},
    (window) => retainDraft(window.localStorage, cached),
  );
  t.after(() => editor.cleanup());
  await settle(editor.act);
  const button = (label: string) => {
    const found = [...editor.container.querySelectorAll("button")].find(
      (item) => item.textContent?.trim() === label,
    );
    assert.ok(found, label);
    return found;
  };
  const body = () =>
    (editor.container.querySelector("textarea") as Control).value;
  assert.equal(body(), "Someone else's newer text");
  assert.equal(patches.length, 0);
  allowed = false;
  await editor.act(async () => button("恢复草稿").click());
  await settle(editor.act);
  assert.equal(body(), "Someone else's newer text");
  assert.match(editor.container.textContent!, /无权/);
  allowed = true;
  await editor.act(async () => button("恢复草稿").click());
  await settle(editor.act);
  assert.equal(body(), cached.source);
  await editor.act(async () => button("保存").click());
  await settle(editor.act);
  assert.equal(patches[0]?.baseCommit, "old-commit");
  assert.equal(patches[0]?.changes[0].expectedRevision, "old-revision");
  assert.equal(patches[0]?.changes[0].content, cached.source);
  assert.equal(body(), cached.source);
});

for (const absent of [false, true]) {
  test(`recovery follows edits back to ${absent ? "an intentionally empty new draft" : "the saved baseline"}`, async (t) => {
    const baseline = absent ? "" : "Saved";
    const editor = await mountEditor(
      "http://localhost/admin?path=private%2Fnote.md",
      async (url) => {
        if (url.pathname.endsWith("/repository/tree"))
          return json({ commit: "before", entries: [], diagnostics: [] });
        if (url.pathname.endsWith("/repository/directory"))
          return directoryResponse("", []);
        if (url.pathname.endsWith("/repository/file"))
          return json({
            path: "private/note.md",
            source: absent ? null : baseline,
            commit: "before",
            revision: absent ? null : "old",
            expectedAbsence: absent,
            publicScope: false,
            diagnostics: [],
          });
        if (url.pathname === "/api/auth/csrf")
          return json({ headerName: "X-CSRF", token: "fixture" });
        if (url.pathname.endsWith("/repository/preview"))
          return json({ body: baseline, galleryStatus: "COMPLETE" });
        throw new Error(`Unexpected ${url.pathname}`);
      },
      () => {},
    );
    t.after(() => editor.cleanup());
    await settle(editor.act);
    const textarea = editor.container.querySelector("textarea")!;
    const write = async (value: string) => {
      await editor.act(async () => {
        Object.getOwnPropertyDescriptor(
          editor.window.HTMLTextAreaElement.prototype,
          "value",
        )!.set!.call(textarea, value);
        textarea.dispatchEvent(
          new editor.window.Event("input", { bubbles: true }),
        );
      });
      await settle(editor.act);
      await editor.act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 300));
      });
    };
    await write("Unsaved");
    assert.equal(
      localDrafts(editor.window.localStorage, "owner", workspaceId)[0].source,
      "Unsaved",
    );
    await write(baseline);
    const retained = localDrafts(
      editor.window.localStorage,
      "owner",
      workspaceId,
    );
    assert.equal(retained.length, absent ? 1 : 0);
    if (absent) assert.equal(retained[0].source, "");
  });
}

test("recovery batches typing, flushes on page hide, and cancels a pending write on unmount", async (t) => {
  let locks = 0;
  const editor = await mountEditor(
    "http://localhost/admin?path=private%2Fnote.md",
    async (url) => {
      if (url.pathname.endsWith("/repository/tree"))
        return json({ commit: "before", entries: [], diagnostics: [] });
      if (url.pathname.endsWith("/repository/directory"))
        return directoryResponse("", []);
      if (url.pathname.endsWith("/repository/file"))
        return json({
          path: "private/note.md",
          source: "Saved",
          commit: "before",
          revision: "old",
          expectedAbsence: false,
          publicScope: false,
          diagnostics: [],
        });
      if (url.pathname === "/api/auth/csrf")
        return json({ headerName: "X-CSRF", token: "fixture" });
      if (url.pathname.endsWith("/repository/preview"))
        return json({ body: "Saved", galleryStatus: "COMPLETE" });
      throw new Error(`Unexpected ${url.pathname}`);
    },
    () => {},
    (window) => {
      Object.defineProperty(window.navigator, "locks", {
        configurable: true,
        value: {
          request: async (_name: string, operation: () => unknown) => {
            locks++;
            return operation();
          },
        },
      });
    },
  );
  t.after(() => editor.cleanup());
  await settle(editor.act);
  const textarea = editor.container.querySelector("textarea")!;
  const write = (value: string) =>
    editor.act(async () => {
      Object.getOwnPropertyDescriptor(
        editor.window.HTMLTextAreaElement.prototype,
        "value",
      )!.set!.call(textarea, value);
      textarea.dispatchEvent(
        new editor.window.Event("input", { bubbles: true }),
      );
    });
  const before = locks;
  for (const value of ["A", "AB", "ABC"]) await write(value);
  assert.equal(locks, before);
  assert.equal(editor.window.localStorage.length, 0);
  await editor.act(async () =>
    editor.window.dispatchEvent(new editor.window.Event("pagehide")),
  );
  assert.equal(locks, before + 1);
  assert.equal(
    localDrafts(editor.window.localStorage, "owner", workspaceId)[0].source,
    "ABC",
  );
  await write("Must not appear after unmount");
  await editor.unmount();
  await new Promise((resolve) => setTimeout(resolve, 350));
  assert.equal(locks, before + 1);
  assert.equal(
    localDrafts(editor.window.localStorage, "owner", workspaceId)[0].source,
    "ABC",
  );
});

test("publish and withdraw use confirmed atomic moves and retain the authoritative website restriction", async (t) => {
  let published = false;
  const moves: unknown[] = [];
  const original =
    "---\nid: 12345678-1234-4234-8234-123456789abc\n---\n# Retained";
  const editor = await mountEditor(
    "http://localhost/admin?path=private%2Fcategory%2Fnote.md",
    async (url, options) => {
      const commit = published ? "published" : "draft";
      if (url.pathname === "/api/auth/csrf")
        return json({ headerName: "X-CSRF", token: "fixture" });
      if (url.pathname.endsWith("/repository/tree"))
        return json({ commit, entries: [], diagnostics: [] });
      if (url.pathname.endsWith("/repository/directory"))
        return directoryResponse("", []);
      if (url.pathname.endsWith("/repository/file"))
        return json({
          path: `${published ? "public" : "private"}/category/note.md`,
          source: original,
          commit,
          revision: "same-revision",
          expectedAbsence: false,
          publicScope: published,
          diagnostics: [],
          publicPage: { state: "WEBSITE_RESTRICTED", space: null, route: null },
        });
      if (url.pathname.endsWith("/repository/preview"))
        return json({ body: "Retained", galleryStatus: "COMPLETE" });
      if (url.pathname.endsWith("/repository/move")) {
        moves.push(JSON.parse(String(options?.body)));
        published = !published;
        return json({
          commit: published ? "published" : "draft",
          committed: true,
          snapshotUpdated: true,
          revisions: {},
        });
      }
      throw new Error(`Unexpected ${url.pathname}`);
    },
    () => {},
  );
  t.after(() => editor.cleanup());
  await settle(editor.act);
  const button = (label: string, dialog = false) => {
    const found = [
      ...editor.container.querySelectorAll(dialog ? "dialog button" : "button"),
    ].find((item) => item.textContent?.trim() === label);
    assert.ok(found, label);
    return found as unknown as Clickable;
  };
  await editor.act(async () => button("发布").click());
  assert.equal(moves.length, 0);
  await editor.act(async () => button("取消", true).click());
  assert.equal(moves.length, 0);
  await editor.act(async () => button("发布").click());
  await editor.act(async () => button("发布", true).click());
  await settle(editor.act);
  assert.deepEqual(moves[0], {
    baseCommit: "draft",
    source: "private/category/note.md",
    destination: "public/category/note.md",
  });
  assert.match(editor.container.textContent!, /公开展示已受限/);
  assert.equal(
    (editor.container.querySelector("textarea") as Control).value,
    original,
  );
  await editor.act(async () => button("撤回为草稿").click());
  await editor.act(async () => button("撤回为草稿", true).click());
  await settle(editor.act);
  assert.deepEqual(moves[1], {
    baseCommit: "published",
    source: "public/category/note.md",
    destination: "private/category/note.md",
  });
  assert.equal(
    (editor.container.querySelector("textarea") as Control).value,
    original,
  );
});

for (const transfer of ["paste", "drop"] as const) {
  test(`${transfer} uploads a draft image once and retries an uncertain response with the same identity`, async (t) => {
    const attempts: string[] = [];
    const pending = deferred<Response>();
    let saved = 0;
    const editor = await mountEditor(
      "http://localhost/admin?path=private%2Fnote.md",
      async (url, options) => {
        if (url.pathname === "/api/auth/csrf")
          return json({ headerName: "X-CSRF", token: "fixture" });
        if (url.pathname.endsWith("/repository/tree"))
          return json({ commit: "before", entries: [], diagnostics: [] });
        if (url.pathname.endsWith("/repository/directory"))
          return directoryResponse("", []);
        if (url.pathname.endsWith("/repository/file"))
          return json({
            path: "private/note.md",
            source: "AB",
            commit: "before",
            revision: "old",
            expectedAbsence: false,
            publicScope: false,
            diagnostics: [],
          });
        if (url.pathname.endsWith("/repository/preview"))
          return json({ body: "AB", galleryStatus: "COMPLETE" });
        if (url.pathname.endsWith("/repository/patch")) saved++;
        if (url.pathname.endsWith("/assets")) {
          attempts.push(new Headers(options?.headers).get("Idempotency-Key")!);
          if (attempts.length === 1)
            throw new TypeError("connection lost after upload");
          return pending.promise;
        }
        throw new Error(`Unexpected ${url.pathname}`);
      },
      () => {},
    );
    t.after(() => editor.cleanup());
    await settle(editor.act);
    const textarea = editor.container.querySelector("textarea")!;
    textarea.setSelectionRange(1, 1);
    const file = new editor.window.File(["fixture"], "test.png", {
      type: "image/png",
    });
    const event = new editor.window.Event(transfer, {
      bubbles: true,
      cancelable: true,
    });
    Object.defineProperty(
      event,
      transfer === "paste" ? "clipboardData" : "dataTransfer",
      { value: { files: [file] } },
    );
    await editor.act(async () => textarea.dispatchEvent(event));
    await settle(editor.act);
    assert.equal(event.defaultPrevented, true);
    assert.equal(attempts.length, 1);
    assert.equal(textarea.value, "AB");
    assert.match(editor.container.textContent!, /连接中断/);
    const retry = [...editor.container.querySelectorAll("button")].find(
      (button) => button.textContent?.trim() === "上传 test.png",
    )!;
    assert.ok(retry);
    await editor.act(async () => retry.click());
    assert.equal(textarea.readOnly, true);
    assert.equal(attempts.length, 2);
    assert.equal(attempts[0], attempts[1]);
    assert.match(attempts[0], /^[0-9a-f-]{36}$/);
    await editor.act(async () =>
      pending.resolve(
        json({ reference: { assetId: "image", revision: "revision" } }),
      ),
    );
    assert.equal(textarea.value, "A\n![图片](managed:image:revision)\nB");
    assert.equal(saved, 0);
    assert.equal(textarea.readOnly, false);
    assert.match(editor.container.textContent!, /图片已上传并插入草稿/);
  });
}

test("an upload acknowledged after leaving the editor cannot recreate a draft", async (t) => {
  const pending = deferred<Response>();
  let uploads = 0;
  const editor = await mountEditor(
    "http://localhost/admin?path=private%2Fnote.md",
    async (url) => {
      if (url.pathname === "/api/auth/csrf")
        return json({ headerName: "X-CSRF", token: "fixture" });
      if (url.pathname.endsWith("/repository/tree"))
        return json({ commit: "before", entries: [], diagnostics: [] });
      if (url.pathname.endsWith("/repository/directory"))
        return directoryResponse("", []);
      if (url.pathname.endsWith("/repository/file"))
        return json({
          path: "private/note.md",
          source: "Unchanged",
          commit: "before",
          revision: "old",
          expectedAbsence: false,
          publicScope: false,
          diagnostics: [],
        });
      if (url.pathname.endsWith("/repository/preview"))
        return json({ body: "Unchanged", galleryStatus: "COMPLETE" });
      if (url.pathname.endsWith("/assets")) {
        uploads++;
        return pending.promise;
      }
      throw new Error(`Unexpected ${url.pathname}`);
    },
    () => {},
  );
  t.after(() => editor.cleanup());
  await settle(editor.act);
  const event = new editor.window.Event("paste", {
    bubbles: true,
    cancelable: true,
  });
  Object.defineProperty(event, "clipboardData", {
    value: {
      files: [
        new editor.window.File(["fixture"], "late.png", { type: "image/png" }),
      ],
    },
  });
  await editor.act(async () =>
    editor.container.querySelector("textarea")!.dispatchEvent(event),
  );
  assert.equal(uploads, 1);
  await editor.unmount();
  await editor.act(async () =>
    pending.resolve(
      json({ reference: { assetId: "late", revision: "revision" } }),
    ),
  );
  assert.equal(editor.container.textContent, "");
  assert.equal(editor.window.localStorage.length, 0);
});

test("opening and saving a file after selecting a folder retains that folder", async (t) => {
  const navigations: { location: ContentLocation; replace?: boolean }[] = [];
  const patches: string[] = [];
  const editor = await mountEditor(
    "http://localhost/admin?path=private%2Fb%2Fold.md&folder=private%2Fb",
    async (url, options) => {
      if (url.pathname === "/api/auth/csrf")
        return json({ headerName: "X-CSRF", token: "fixture" });
      if (url.pathname.endsWith("/repository/tree"))
        return json({ commit: "before", entries: [], diagnostics: [] });
      if (url.pathname.endsWith("/repository/directory")) {
        const path = url.searchParams.get("path") ?? "";
        return path === ""
          ? directoryResponse(path, [{ path: "private", kind: "DIRECTORY" }])
          : path === "private"
            ? directoryResponse(path, [
                { path: "private/b", kind: "DIRECTORY" },
                { path: "private/a.md", kind: "FILE" },
              ])
            : directoryResponse(path, [
                { path: "private/b/old.md", kind: "FILE" },
              ]);
      }
      if (url.pathname.endsWith("/repository/file")) {
        const path = url.searchParams.get("path")!;
        return json({
          path,
          source: `# ${path}`,
          revision: "revision",
          commit: "before",
          expectedAbsence: false,
          publicScope: false,
          diagnostics: [],
        });
      }
      if (url.pathname.endsWith("/repository/preview"))
        return json({
          body: "# preview",
          images: {},
          galleryStatus: "COMPLETE",
        });
      if (url.pathname.endsWith("/repository/patch")) {
        const body = JSON.parse(String(options?.body)) as {
          changes: { path: string }[];
        };
        patches.push(body.changes[0].path);
        return json({
          commit: "after",
          committed: true,
          snapshotUpdated: false,
          revisions: { [body.changes[0].path]: "after-revision" },
        });
      }
      throw new Error(`Unexpected API call: ${url.pathname}`);
    },
    (location, replace) => navigations.push({ location, replace }),
  );
  t.after(() => editor.cleanup());
  await settle(editor.act);
  navigations.length = 0;
  const folderSummary = [...editor.container.querySelectorAll("summary")].find(
    (item) => item.textContent?.trim() === "b",
  );
  assert.ok(folderSummary);
  await editor.act(async () => folderSummary.click());
  const fileButton = [
    ...editor.container.querySelectorAll(".tree-file-row button"),
  ].find((item) => item.textContent?.trim().includes("a.md"));
  assert.ok(fileButton);
  await editor.act(async () => (fileButton as unknown as Clickable).click());
  await settle(editor.act);
  assert.deepEqual(navigations.at(-1), {
    location: { folder: "private/b", path: "private/a.md" },
    replace: false,
  });
  const textarea = editor.container.querySelector("textarea") as Control | null;
  assert.ok(textarea);
  Object.getOwnPropertyDescriptor(
    editor.window.HTMLTextAreaElement.prototype,
    "value",
  )!.set!.call(textarea, "# Edited");
  await editor.act(async () =>
    textarea.dispatchEvent(new editor.window.Event("input", { bubbles: true })),
  );
  const save = [...editor.container.querySelectorAll("button")].find(
    (item) => item.textContent?.trim() === "保存",
  );
  assert.ok(save);
  await editor.act(async () => save.click());
  await settle(editor.act);
  assert.deepEqual(patches, ["private/a.md"]);
  assert.deepEqual(navigations.at(-1), {
    location: { folder: "private/b", path: "private/a.md" },
    replace: true,
  });
});

test("a server case-fold conflict keeps the edited draft in place", async (t) => {
  let patchAttempts = 0;
  const editor = await mountEditor(
    "http://localhost/admin?path=private%2Fnote.md",
    async (url) => {
      if (url.pathname === "/api/auth/csrf")
        return json({ headerName: "X-CSRF", token: "fixture" });
      if (url.pathname.endsWith("/repository/tree"))
        return json({ commit: "before", entries: [], diagnostics: [] });
      if (url.pathname.endsWith("/repository/directory"))
        return directoryResponse(url.searchParams.get("path") ?? "", []);
      if (url.pathname.endsWith("/repository/file"))
        return json({
          path: "private/note.md",
          source: "# Original",
          revision: "revision",
          commit: "before",
          expectedAbsence: false,
          publicScope: false,
          diagnostics: [],
        });
      if (url.pathname.endsWith("/repository/preview"))
        return json({
          body: "# preview",
          images: {},
          galleryStatus: "COMPLETE",
        });
      if (url.pathname.endsWith("/repository/patch")) {
        patchAttempts += 1;
        return Response.json({ code: "CASE_FOLD_CONFLICT" }, { status: 409 });
      }
      throw new Error(`Unexpected API call: ${url.pathname}`);
    },
    () => {},
  );
  t.after(() => editor.cleanup());
  await settle(editor.act);
  const textarea = editor.container.querySelector("textarea") as Control | null;
  assert.ok(textarea);
  Object.getOwnPropertyDescriptor(
    editor.window.HTMLTextAreaElement.prototype,
    "value",
  )!.set!.call(textarea, "# Draft survives conflict");
  await editor.act(async () =>
    textarea.dispatchEvent(new editor.window.Event("input", { bubbles: true })),
  );
  const save = [...editor.container.querySelectorAll("button")].find(
    (item) => item.textContent?.trim() === "保存",
  );
  assert.ok(save);
  await editor.act(async () => save.click());
  await settle(editor.act);
  assert.equal(patchAttempts, 1);
  assert.equal(textarea.value, "# Draft survives conflict");
  assert.match(editor.container.textContent!, /操作与当前状态冲突/);
});

test("moving a folder maps a selected descendant but preserves an unrelated folder", async (t) => {
  let moved = false;
  const moves: { source: string; destination: string }[] = [];
  const navigations: ContentLocation[] = [];
  const editor = await mountEditor(
    "http://localhost/admin?tab=content&folder=private%2Fold%2Fchild",
    async (url, options) => {
      if (url.pathname === "/api/auth/csrf")
        return json({ headerName: "X-CSRF", token: "fixture" });
      if (url.pathname.endsWith("/repository/tree"))
        return json({
          commit: moved ? "after" : "before",
          entries: [],
          diagnostics: [],
        });
      if (url.pathname.endsWith("/repository/directory")) {
        const path = url.searchParams.get("path") ?? "";
        const entries =
          path === ""
            ? moved
              ? [
                  { path: "private", kind: "DIRECTORY" as const },
                  { path: "archive", kind: "DIRECTORY" as const },
                ]
              : [{ path: "private", kind: "DIRECTORY" as const }]
            : path === "private"
              ? moved
                ? [{ path: "private/other", kind: "DIRECTORY" as const }]
                : [
                    { path: "private/old", kind: "DIRECTORY" as const },
                    { path: "private/other", kind: "DIRECTORY" as const },
                  ]
              : path === "private/old"
                ? [{ path: "private/old/child", kind: "DIRECTORY" as const }]
                : path === "archive"
                  ? moved
                    ? [{ path: "archive/old", kind: "DIRECTORY" as const }]
                    : []
                  : path === "archive/old"
                    ? [
                        {
                          path: "archive/old/child",
                          kind: "DIRECTORY" as const,
                        },
                      ]
                    : [];
        return directoryResponse(
          path,
          entries,
          false,
          moved ? "after" : "before",
        );
      }
      if (url.pathname.endsWith("/repository/move")) {
        const body = JSON.parse(String(options?.body)) as {
          source: string;
          destination: string;
        };
        moves.push(body);
        moved = true;
        return json({
          commit: "after",
          committed: true,
          snapshotUpdated: false,
          revisions: {},
        });
      }
      throw new Error(`Unexpected API call: ${url.pathname}`);
    },
    (location) => navigations.push(location),
  );
  t.after(() => editor.cleanup());
  await settle(editor.act);
  const moveFolder = (path: string) => {
    const button = editor.container.querySelector(
      `button[aria-label="移动分类 ${path}"]`,
    );
    assert.ok(button, path);
    return button as unknown as Clickable;
  };
  await editor.act(async () => moveFolder("private/old").click());
  await settle(editor.act);
  const firstDialog = editor.container.querySelector("dialog[open]");
  assert.ok(firstDialog);
  const rootButton = [...firstDialog.querySelectorAll("button")].find(
    (item) => item.textContent?.trim() === "最外层",
  );
  assert.ok(rootButton);
  await editor.act(async () => rootButton.click());
  await settle(editor.act);
  const newFolder = [...firstDialog.querySelectorAll("input")].find((item) =>
    item.parentElement?.textContent?.includes("新建下一级分类"),
  );
  assert.ok(newFolder);
  Object.getOwnPropertyDescriptor(
    editor.window.HTMLInputElement.prototype,
    "value",
  )!.set!.call(newFolder, "archive");
  await editor.act(async () =>
    newFolder.dispatchEvent(
      new editor.window.Event("input", { bubbles: true }),
    ),
  );
  const firstMove = [...firstDialog.querySelectorAll("button")].find(
    (item) => item.textContent?.trim() === "移动到这里",
  );
  assert.ok(firstMove);
  await editor.act(async () => firstMove.click());
  await settle(editor.act);
  assert.deepEqual(moves[0], {
    baseCommit: "before",
    source: "private/old",
    destination: "archive/old",
  });
  assert.deepEqual(navigations.at(-1), {
    folder: "archive/old/child",
    path: "",
  });
  const privateSummary = [...editor.container.querySelectorAll("summary")].find(
    (item) => item.textContent?.trim() === "草稿",
  );
  assert.ok(privateSummary);
  await editor.act(async () => privateSummary.click());
  await settle(editor.act);
  const unrelated = [...editor.container.querySelectorAll("summary")].find(
    (item) => item.textContent?.trim() === "other",
  );
  assert.ok(unrelated);
  await editor.act(async () => unrelated.click());
  await editor.act(async () => moveFolder("archive/old").click());
  await settle(editor.act);
  const secondDialog = editor.container.querySelector("dialog[open]");
  assert.ok(secondDialog);
  const secondName = secondDialog.querySelector("input") as Control | null;
  assert.ok(secondName);
  Object.getOwnPropertyDescriptor(
    editor.window.HTMLInputElement.prototype,
    "value",
  )!.set!.call(secondName, "new");
  await editor.act(async () =>
    secondName.dispatchEvent(
      new editor.window.Event("input", { bubbles: true }),
    ),
  );
  const secondMove = [...secondDialog.querySelectorAll("button")].find(
    (item) => item.textContent?.trim() === "移动到这里",
  );
  assert.ok(secondMove);
  await editor.act(async () => secondMove.click());
  await settle(editor.act);
  assert.deepEqual(moves[1], {
    baseCommit: "after",
    source: "archive/old",
    destination: "archive/new",
  });
  assert.deepEqual(navigations.at(-1), {
    folder: "private/other",
    path: "",
  });
});

test("an editor unmount prevents a late file response from changing navigation", async (t) => {
  const tree = deferred<Response>();
  const file = deferred<Response>();
  const navigations: ContentLocation[] = [];
  const requested: string[] = [];
  const editor = await mountEditor(
    "http://localhost/admin?path=private%2Flate.md",
    async (url) => {
      if (url.pathname.endsWith("/repository/tree")) {
        requested.push("tree");
        return tree.promise;
      }
      if (url.pathname.endsWith("/repository/file")) {
        requested.push("file");
        return file.promise;
      }
      if (url.pathname.endsWith("/repository/directory"))
        return directoryResponse("", []);
      throw new Error(`Unexpected API call: ${url.pathname}`);
    },
    (location) => navigations.push(location),
  );
  t.after(() => editor.cleanup());
  await editor.act(async () => {
    tree.resolve(
      json({
        commit: "before",
        entries: [],
        diagnostics: [],
      }),
    );
    await Promise.resolve();
  });
  await settle(editor.act);
  assert.deepEqual(requested, ["tree", "file"]);
  await editor.unmount();
  file.resolve(
    json({
      path: "private/late.md",
      source: "# Late",
      revision: "revision",
      commit: "before",
      expectedAbsence: false,
      publicScope: false,
      diagnostics: [],
    }),
  );
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.deepEqual(navigations, []);
});

test("history selection ignores late reads and restoration saves against the current baseline", async (t) => {
  const oldRead = deferred<Response>();
  const patches: unknown[] = [];
  const current = {
    path: "private/note.md",
    source: "# Current",
    revision: "current-revision",
    commit: "current-commit",
    expectedAbsence: false,
    publicScope: false,
    diagnostics: [],
    publicPage: null,
  };
  const historical = "# Historical B\n<script>plain source</script>";
  const editor = await mountEditor(
    "http://localhost/admin?path=private%2Fnote.md",
    async (url, options) => {
      if (url.pathname === "/api/auth/csrf")
        return json({ headerName: "X-CSRF", token: "fixture" });
      if (url.pathname.endsWith("/repository/tree"))
        return json({ commit: current.commit, entries: [], diagnostics: [] });
      if (url.pathname.endsWith("/repository/directory"))
        return directoryResponse("", []);
      if (url.pathname.endsWith("/repository/preview"))
        return json({ body: "", images: {}, galleryStatus: "COMPLETE" });
      if (url.pathname.endsWith("/repository/history"))
        return json({
          commit: current.commit,
          path: current.path,
          nextOffset: null,
          entries: ["older-a", "older-b"].map((commit) => ({
            commit,
            subject: commit,
            author: "Author",
            committedAt: "2026-09-01T00:00:00Z",
            present: true,
          })),
        });
      if (url.pathname.endsWith("/repository/file")) {
        if (url.searchParams.get("commit") === "older-a")
          return oldRead.promise;
        if (url.searchParams.get("commit") === "older-b")
          return json({
            ...current,
            source: historical,
            commit: "older-b",
            revision: "old-revision",
          });
        return json(current);
      }
      if (url.pathname.endsWith("/repository/patch")) {
        patches.push(JSON.parse(String(options?.body)));
        return new Response(null, { status: 409 });
      }
      throw new Error(`Unexpected API call: ${url.pathname}`);
    },
    () => {},
  );
  t.after(() => editor.cleanup());
  await settle(editor.act);
  const textarea = editor.container.querySelector("textarea") as Control;
  Object.getOwnPropertyDescriptor(
    editor.window.HTMLTextAreaElement.prototype,
    "value",
  )!.set!.call(textarea, "# Unsaved");
  await editor.act(async () =>
    textarea.dispatchEvent(new editor.window.Event("input", { bubbles: true })),
  );
  const button = (label: string) => {
    const found = [...editor.container.querySelectorAll("button")].find(
      (item) => item.textContent?.trim() === label,
    );
    assert.ok(found, label);
    return found;
  };
  await editor.act(async () => button("历史版本").click());
  await settle(editor.act);
  const revision = (name: string) => {
    const found = [
      ...editor.container.querySelectorAll(".history-versions button"),
    ].find((item) => item.querySelector("strong")?.textContent === name);
    assert.ok(found, name);
    return found as unknown as Clickable;
  };
  await editor.act(async () => revision("older-a").click());
  await editor.act(async () => revision("older-b").click());
  await settle(editor.act);
  assert.match(editor.container.textContent, /Historical B/);
  assert.equal(editor.container.querySelectorAll("script").length, 0);
  await editor.act(async () =>
    oldRead.resolve(
      json({ ...current, source: "# Stale A", commit: "older-a" }),
    ),
  );
  assert.doesNotMatch(editor.container.textContent, /Stale A/);
  await editor.act(async () => button("恢复到编辑框").click());
  assert.match(editor.container.textContent, /用历史版本替换当前编辑内容/);
  await editor.act(async () => button("取消").click());
  assert.equal(textarea.value, "# Unsaved");
  await editor.act(async () => button("恢复到编辑框").click());
  await editor.act(async () => button("替换编辑内容").click());
  assert.equal(textarea.value, historical);
  assert.deepEqual(patches, []);
  assert.equal(editor.container.querySelector(".history-dialog"), null);
  await editor.act(async () => button("保存").click());
  await settle(editor.act);
  assert.deepEqual(patches, [
    {
      baseCommit: "current-commit",
      changes: [
        {
          path: current.path,
          expectedAbsence: false,
          expectedRevision: "current-revision",
          content: historical,
        },
      ],
    },
  ]);
  assert.equal(textarea.value, historical);
  assert.match(editor.container.textContent, /编辑框中的内容仍然保留/);
});
