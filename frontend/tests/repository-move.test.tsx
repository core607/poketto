import assert from "node:assert/strict";
import test from "node:test";
import { Window } from "happy-dom";
import {
  contentRoot,
  inContentRoot,
  movablePath,
} from "../lib/repository-directory";

test("scope switches preserve categories and only interpret the exact content root", () => {
  assert.equal(
    inContentRoot("private/reading/novels", "public"),
    "public/reading/novels",
  );
  assert.equal(
    inContentRoot("public/private/album", "private"),
    "private/private/album",
  );
  assert.equal(inContentRoot("private", "public"), "public");
  assert.equal(
    inContentRoot("notes/public", "private"),
    "private/notes/public",
  );
  assert.equal(inContentRoot("Public/notes", "public"), "public/Public/notes");
  assert.equal(contentRoot("Public/notes"), null);
  assert.equal(contentRoot("private/public/notes"), "private");
});

test("move controls preserve case-insensitive reserved roots and allow their children", () => {
  for (const path of [
    "public",
    "Public",
    "private",
    "Private",
    "prıvate",
    "publıc",
    ".POKETTO/assets.json",
  ])
    assert.equal(movablePath(path), false);
  for (const path of ["Public/article.md", "Private/note.md", "notes/Public"])
    assert.equal(movablePath(path), true);
});

test("folder selection cancels without writing and moves via the host service before rereading repaired text", async (t) => {
  const window = new Window({
    url: "http://localhost/admin?path=private%2Fnote.md",
  });
  const globals = {
    window,
    document: window.document,
    navigator: window.navigator,
    HTMLElement: window.HTMLElement,
    HTMLDialogElement: window.HTMLDialogElement,
    Event: window.Event,
    IS_REACT_ACT_ENVIRONMENT: true,
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
  const { act } = await import("react");
  const { createRoot } = await import("react-dom/client");
  const { Editor } = await import("../components/editor");
  const { ConfirmationProvider } = await import("../components/confirmation");
  const container = window.document.createElement("div");
  window.document.body.append(container);
  const root = createRoot(container as unknown as HTMLDivElement);
  const oldFetch = globalThis.fetch;
  let moved = false;
  let stale = false;
  let refreshed = false;
  const writes: unknown[] = [];
  const directoryVersions: string[] = [];
  globalThis.fetch = async (input, options) => {
    const url = new URL(String(input), "http://localhost");
    const commit = refreshed ? "concurrent" : moved ? "after" : "before";
    const path = moved ? "public/renamed.md" : "private/note.md";
    if (url.pathname === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    if (url.pathname === "/api/admin/repository/tree")
      return Response.json({
        commit,
        entries: [{ path, title: "Note" }],
        diagnostics: [],
      });
    if (url.pathname === "/api/admin/repository/file") {
      assert.equal(url.searchParams.get("path"), path);
      return Response.json({
        commit,
        path,
        source: moved
          ? "# Note\n[manual](manual.md)"
          : "# Note\n[manual](../public/manual.md)",
        revision: commit,
        expectedAbsence: false,
        diagnostics: [],
      });
    }
    if (url.pathname === "/api/admin/repository/directory") {
      const folder = url.searchParams.get("path")!;
      directoryVersions.push(url.searchParams.get("commit")!);
      const entries =
        folder === ""
          ? [
              { path: "private", kind: "DIRECTORY" },
              { path: "public", kind: "DIRECTORY" },
            ]
          : folder === "private"
            ? [{ path: "private/note.md", kind: "FILE" }]
            : [{ path: "public/manual.md", kind: "FILE" }];
      return Response.json({
        commit: url.searchParams.get("commit"),
        path: folder,
        entries,
        expectedAbsence: false,
        nextOffset: null,
      });
    }
    if (url.pathname === "/api/admin/repository/move") {
      assert.equal(options?.method, "POST");
      assert.equal(new Headers(options.headers).get("X-CSRF"), "fixture");
      writes.push(JSON.parse(String(options.body)));
      if (writes.length === 1) return new Response(null, { status: 400 });
      if (stale) {
        refreshed = true;
        return new Response(null, { status: 409 });
      }
      moved = true;
      return Response.json({
        commit: "after",
        committed: true,
        snapshotUpdated: true,
        revisions: {},
      });
    }
    if (url.pathname === "/api/admin/repository/preview")
      return Response.json({ body: "# Note", galleryStatus: "COMPLETE" });
    throw new Error(`Unexpected API call: ${url.pathname}`);
  };
  t.after(async () => {
    await act(async () => root.unmount());
    globalThis.fetch = oldFetch;
    await window.happyDOM.close();
    for (const [name, descriptor] of previous) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else Reflect.deleteProperty(globalThis, name);
    }
  });
  await act(async () =>
    root.render(
      <ConfirmationProvider>
        <Editor
          identity={{
            accountId: "owner",
            workspaceId: "workspace",
            role: "OWNER",
            capabilities: ["READ_PRIVATE", "WRITE_PRIVATE", "PUBLISH"],
          }}
          onDirtyChange={() => {}}
        />
      </ConfirmationProvider>,
    ),
  );
  const button = (label: string, scope = container) => {
    const item = [...scope.querySelectorAll("button")].find(
      (item) => item.textContent?.trim() === label,
    );
    assert.ok(item, `Missing button: ${label}`);
    return item;
  };
  const trigger = button("移动…");
  assert.equal(
    container.querySelector('input[name="path"]')?.getAttribute("value"),
    "private/",
  );
  await act(async () => trigger.click());
  let dialog = container.querySelector("dialog[open]");
  assert.ok(dialog);
  assert.equal(writes.length, 0);
  await act(async () =>
    dialog!.dispatchEvent(new window.Event("cancel", { cancelable: true })),
  );
  assert.equal(writes.length, 0);
  assert.ok(
    !container.querySelector("dialog[open]"),
    "Cancel closes the dialog",
  );
  assert.ok(
    window.document.activeElement === trigger,
    "Cancel restores focus to its trigger",
  );
  await act(async () => trigger.click());
  dialog = container.querySelector("dialog[open]");
  assert.ok(dialog);
  await act(async () =>
    button("公开目录", dialog! as typeof container).click(),
  );
  assert.match(dialog.textContent!, /目标：public\/note.md/);
  assert.equal(
    button("公开目录", dialog! as typeof container).getAttribute(
      "aria-pressed",
    ),
    "true",
  );
  await act(async () =>
    button("私有目录", dialog! as typeof container).click(),
  );
  assert.match(dialog.textContent!, /目标：private\/note.md/);
  assert.ok(button("移动到这里", dialog! as typeof container).disabled);
  await act(async () =>
    button("公开目录", dialog! as typeof container).click(),
  );
  assert.equal(writes.length, 0);
  await act(async () =>
    button("移动到这里", dialog! as typeof container).click(),
  );
  assert.equal(
    writes.length,
    1,
    "A failed move is never retried automatically",
  );
  assert.ok(container.querySelector("dialog[open]"));
  assert.match(dialog!.textContent!, /输入格式有误/);
  const name = dialog!.querySelector("input");
  assert.ok(name);
  await act(async () => {
    Object.getOwnPropertyDescriptor(
      window.HTMLInputElement.prototype,
      "value",
    )!.set!.call(name, "renamed.md");
    name.dispatchEvent(new window.Event("input", { bubbles: true }));
  });
  assert.ok(!button("移动到这里", dialog! as typeof container).disabled);
  await act(async () =>
    button("移动到这里", dialog! as typeof container).click(),
  );
  assert.deepEqual(writes, [
    {
      baseCommit: "before",
      source: "private/note.md",
      destination: "public/note.md",
    },
    {
      baseCommit: "before",
      source: "private/note.md",
      destination: "public/renamed.md",
    },
  ]);
  assert.equal(
    container.querySelector("textarea")?.value,
    "# Note\n[manual](manual.md)",
  );
  assert.match(container.textContent!, /已移动，相关链接已更新/);
  assert.ok(
    !container.querySelector("dialog[open]"),
    "Successful move closes the dialog",
  );
  assert.ok(
    directoryVersions.every(
      (version) => version === "before" || version === "after",
    ),
  );
  assert.ok(
    window.document.activeElement === trigger ||
      window.document.activeElement ===
        container.querySelector(".editor-layout"),
    `Focus returns to the surviving trigger or the editor fallback; actual=${window.document.activeElement?.tagName}.${window.document.activeElement?.className}; trigger connected=${trigger.isConnected}`,
  );
  stale = true;
  await act(async () => button("移动…").click());
  dialog = container.querySelector("dialog[open]");
  assert.ok(dialog);
  await act(async () =>
    button("私有目录", dialog! as typeof container).click(),
  );
  await act(async () =>
    button("移动到这里", dialog! as typeof container).click(),
  );
  assert.equal(writes.length, 3, "A stale base is not retried automatically");
  assert.deepEqual(writes[2], {
    baseCommit: "after",
    source: "public/renamed.md",
    destination: "private/renamed.md",
  });
  assert.ok(!container.querySelector("dialog[open]"));
  assert.match(container.textContent!, /仓库内容已改变，目录已刷新/);
  assert.equal(container.querySelector("textarea"), null);
  assert.equal(directoryVersions.at(-1), "concurrent");
  const expandedPaths = [
    ...container.querySelectorAll("details[open] > summary"),
  ].map((item) => item.textContent);
  assert.ok(
    expandedPaths.includes("private"),
    "The originally selected directory stays expanded across commits",
  );
  assert.ok(
    expandedPaths.includes("public"),
    "The moved file's directory expands and survives a later conflict",
  );
});
