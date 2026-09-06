import assert from "node:assert/strict";
import test from "node:test";
import { Window } from "happy-dom";
import { relativePath } from "../components/asset-picker";

test("repository image destinations encode filename bytes and retain relative parents", () => {
  for (const [name, encoded] of [
    ["photo#1.png", "photo%231.png"],
    ["100%.png", "100%25.png"],
    ["what?.png", "what%3F.png"],
    ["photo one.png", "photo%20one.png"],
    ["图片.png", "%E5%9B%BE%E7%89%87.png"],
    ["photo(one).png", "photo(one).png"],
  ]) {
    assert.equal(relativePath("notes/a.md", `notes/${name}`), encoded);
    assert.equal(
      relativePath("archive/deep/a.md", `notes/${name}`),
      `../../notes/${encoded}`,
    );
  }
});

test("editor inserts and previews new images relative to the pending destination without rewriting the draft", async (t) => {
  const window = new Window({
    url: "http://localhost/admin?path=notes%2Fa.md",
  });
  const names = [
    "window",
    "document",
    "navigator",
    "HTMLElement",
    "HTMLInputElement",
    "Event",
    "IS_REACT_ACT_ENVIRONMENT",
  ];
  const saved = new Map(
    names.map((name) => [
      name,
      Object.getOwnPropertyDescriptor(globalThis, name),
    ]),
  );
  for (const [name, value] of Object.entries({
    window,
    document: window.document,
    navigator: window.navigator,
    HTMLElement: window.HTMLElement,
    HTMLInputElement: window.HTMLInputElement,
    Event: window.Event,
    IS_REACT_ACT_ENVIRONMENT: true,
  })) {
    Object.defineProperty(globalThis, name, {
      configurable: true,
      writable: true,
      value,
    });
  }
  const { act } = await import("react");
  const { createRoot } = await import("react-dom/client");
  const { Editor } = await import("../components/editor");
  const { ConfirmationProvider } = await import("../components/confirmation");
  const container = window.document.createElement("div");
  window.document.body.append(container);
  const root = createRoot(container as unknown as HTMLDivElement);
  const original = "# Existing\n\n![Keep](../keep.png)\n";
  const previews: { path: string; body: string; commit: string }[] = [];
  const previousFetch = globalThis.fetch;
  globalThis.fetch = async (input, options) => {
    const url = new URL(String(input), "http://localhost");
    if (url.pathname === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    if (url.pathname === "/api/admin/repository/tree")
      return Response.json({
        commit: "before",
        entries: [{ path: "notes/a.md", title: "Existing" }],
        diagnostics: [],
      });
    if (url.pathname === "/api/admin/repository/file")
      return Response.json({
        path: "notes/a.md",
        source: original,
        revision: "revision",
        commit: "before",
        expectedAbsence: false,
        diagnostics: [],
      });
    if (url.pathname === "/api/admin/assets/repository")
      return Response.json({
        items: [
          { path: "notes/photo#1?.png", mediaType: "image/png", size: 73 },
        ],
        total: 1,
      });
    if (url.pathname === "/api/admin/repository/preview") {
      const request = JSON.parse(String(options?.body));
      previews.push(request);
      return Response.json({
        body: request.body,
        images: {},
        links: {},
        gallery: [],
      });
    }
    throw new Error(`Unexpected test API: ${url.pathname}`);
  };
  t.after(async () => {
    await act(async () => root.unmount());
    globalThis.fetch = previousFetch;
    await window.happyDOM.close();
    for (const name of names) {
      const descriptor = saved.get(name);
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
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 550));
  });
  assert.equal(previews.at(-1)?.path, "notes/a.md");
  const button = (label: string) => {
    const value = [...container.querySelectorAll("button")].find(
      (item) => item.textContent?.trim() === label,
    );
    assert.ok(value, label);
    return value;
  };
  await act(async () => button("选择仓库图片").click());
  const pathInput = container.querySelector(".path-label input");
  assert.ok(pathInput);
  await act(async () => {
    Object.getOwnPropertyDescriptor(
      window.HTMLInputElement.prototype,
      "value",
    )!.set!.call(pathInput, "archive/a.md");
    pathInput.dispatchEvent(new window.Event("input", { bubbles: true }));
  });
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 550));
  });
  assert.equal(previews.at(-1)?.path, "archive/a.md");
  assert.equal(previews.at(-1)?.body, original);
  assert.equal(
    container.querySelector(".asset-shelf"),
    null,
    "old image choices must not retain the previous path context",
  );
  await act(async () => button("选择仓库图片").click());
  assert.deepEqual(previews.at(-1), {
    path: "archive/a.md",
    body: "![](<../notes/photo%231%3F.png>)",
    commit: "before",
  });
  const textarea = container.querySelector("textarea");
  assert.ok(textarea);
  textarea.setSelectionRange(original.length, original.length);
  await act(async () => button("▧notes/photo#1?.png").click());
  const expected = original + "\n![图片](<../notes/photo%231%3F.png>)\n";
  assert.equal(textarea.value, expected);
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 550));
  });
  assert.deepEqual(previews.at(-1), {
    path: "archive/a.md",
    body: expected,
    commit: "before",
  });
});
