import assert from "node:assert/strict";
import test from "node:test";
import { Window } from "happy-dom";
import { scopedRoot } from "./workspace-fixture";

for (const scenario of [
  {
    name: "public publisher",
    capabilities: ["PUBLISH"],
    publicScope: true,
    writable: true,
    picker: false,
    upload: false,
  },
  {
    name: "publisher with private read",
    capabilities: ["PUBLISH", "READ_PRIVATE"],
    publicScope: true,
    writable: true,
    picker: true,
    upload: false,
  },
  {
    name: "excluded file",
    capabilities: ["PUBLISH", "READ_PRIVATE"],
    publicScope: false,
    writable: false,
    picker: false,
    upload: false,
  },
  {
    name: "private writer at public file",
    capabilities: ["READ_PRIVATE", "WRITE_PRIVATE"],
    publicScope: true,
    writable: false,
    picker: false,
    upload: false,
  },
  {
    name: "private writer at excluded file",
    capabilities: ["READ_PRIVATE", "WRITE_PRIVATE"],
    publicScope: false,
    writable: true,
    picker: true,
    upload: true,
  },
]) {
  test(`editor respects authoritative file scope for ${scenario.name}`, async (t) => {
    const window = new Window({
      url: "https://site.example/admin?path=public/note.md",
    });
    const globals = {
      window,
      document: window.document,
      navigator: window.navigator,
      HTMLElement: window.HTMLElement,
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
    const previousFetch = globalThis.fetch;
    globalThis.fetch = async (input) => {
      const url = new URL(String(input), "https://site.example");
      if (url.pathname.endsWith("/repository/tree"))
        return Response.json({
          commit: "current",
          entries: [],
          diagnostics: [],
        });
      if (url.pathname.endsWith("/repository/directory"))
        return Response.json({
          commit: "current",
          path: "",
          entries: [],
          nextOffset: null,
        });
      if (url.pathname.endsWith("/repository/file"))
        return Response.json({
          path: "public/note.md",
          source: "# Note",
          revision: "revision",
          commit: "current",
          expectedAbsence: false,
          publicScope: scenario.publicScope,
          diagnostics: [],
        });
      throw new Error("Unexpected request " + url.pathname);
    };
    const { act } = await import("react");
    const { createRoot } = await import("react-dom/client");
    const { Editor } = await import("../components/editor");
    const { ConfirmationProvider } = await import("../components/confirmation");
    const container = window.document.createElement("div");
    window.document.body.append(container);
    const root = scopedRoot(createRoot(container as unknown as HTMLDivElement));
    t.after(async () => {
      await act(async () => root.unmount());
      await window.happyDOM.close();
      globalThis.fetch = previousFetch;
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
              accountId: "member",
              workspaceId: "11111111-1111-4111-8111-111111111111",
              displayName: "Member space",
              role: "MEMBER",
              capabilities: scenario.capabilities,
            }}
            onDirtyChange={() => {}}
          />
        </ConfirmationProvider>,
      ),
    );
    const textarea = container.querySelector("textarea");
    assert.ok(textarea);
    assert.equal(textarea.readOnly, !scenario.writable);
    assert.equal(
      container.textContent.includes("选择已上传图片"),
      scenario.picker,
    );
    assert.equal(
      !!container.querySelector('input[type="file"]'),
      scenario.upload,
    );
    if (scenario.writable) {
      const move = Array.from(container.querySelectorAll("button")).find(
        (button) => button.textContent === "移动…",
      );
      assert.ok(move);
      await act(async () => move.click());
      const privateRoot = Array.from(container.querySelectorAll("button")).find(
        (button) => button.textContent === "私有目录",
      );
      const publicRoot = Array.from(container.querySelectorAll("button")).find(
        (button) => button.textContent === "公开目录",
      );
      assert.ok(privateRoot);
      assert.ok(publicRoot);
      assert.equal(
        privateRoot.disabled,
        !scenario.capabilities.includes("WRITE_PRIVATE"),
      );
      assert.equal(
        publicRoot.disabled,
        !scenario.capabilities.includes("PUBLISH"),
      );
    }
  });
}
