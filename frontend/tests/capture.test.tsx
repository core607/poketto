import assert from "node:assert/strict";
import test, { type TestContext } from "node:test";
import { Window, type HTMLTextAreaElement } from "happy-dom";
import { scopedRoot, workspaceId } from "./workspace-fixture";

async function dom(t: TestContext) {
  const window = new Window({ url: "https://site.example/capture" });
  const globals = {
    window,
    document: window.document,
    navigator: window.navigator,
    localStorage: window.localStorage,
    HTMLElement: window.HTMLElement,
    HTMLInputElement: window.HTMLInputElement,
    HTMLTextAreaElement: window.HTMLTextAreaElement,
    HTMLSelectElement: window.HTMLSelectElement,
    FormData: window.FormData,
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
  const container = window.document.createElement("div");
  window.document.body.append(container);
  const root = createRoot(container as unknown as HTMLDivElement);
  const savedFetch = globalThis.fetch;
  t.after(async () => {
    await act(async () => root.unmount());
    globalThis.fetch = savedFetch;
    await window.happyDOM.close();
    for (const [name, value] of previous) {
      if (value) Object.defineProperty(globalThis, name, value);
      else Reflect.deleteProperty(globalThis, name);
    }
  });
  return { window, act, root, container };
}

const spaces = {
  items: [
    {
      workspaceId: "22222222-2222-4222-8222-222222222222",
      displayName: "只读空间",
      role: "MEMBER",
      capabilities: ["READ_PRIVATE"],
    },
    {
      workspaceId,
      displayName: "三里屯分部",
      role: "MEMBER",
      capabilities: ["READ_PRIVATE", "WRITE_PRIVATE"],
    },
  ],
  total: 2,
  offset: 0,
  limit: 30,
};

test("the capture popup offers writable spaces and saves only on submit", async (t) => {
  const f = await dom(t);
  const posts: { path: string; body: unknown }[] = [];
  globalThis.fetch = async (input, options) => {
    const path = String(input);
    if (path === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    if (path === "/api/auth/workspaces") return Response.json(spaces);
    posts.push({ path, body: JSON.parse(String(options?.body)) });
    return Response.json(
      { path: "private/inbox/2026-09-24-0720-雨后.md", commit: "a".repeat(40) },
      { status: 201 },
    );
  };
  const { CaptureForm } = await import("../components/capture-form");
  await f.act(async () =>
    f.root.render(
      <CaptureForm title="雨后" url="https://example.com/rain" text="一段话" />,
    ),
  );
  const options = Array.from(f.container.querySelectorAll("option"));
  assert.deepEqual(
    options.map((option) => option.textContent),
    ["三里屯分部"],
  );
  assert.equal(posts.length, 0);

  const form = f.container.querySelector("form")!;
  f.container.querySelector<HTMLTextAreaElement>(
    'textarea[name="note"]',
  )!.value = "我的备注";
  await f.act(async () =>
    form.dispatchEvent(
      new f.window.Event("submit", { bubbles: true, cancelable: true }),
    ),
  );
  assert.deepEqual(posts, [
    {
      path: `/api/admin/workspaces/${workspaceId}/capture`,
      body: {
        title: "雨后",
        url: "https://example.com/rain",
        text: "一段话",
        note: "我的备注",
      },
    },
  ]);
  assert.match(f.container.textContent, /已存入口袋/);
  assert.equal(
    f.window.localStorage.getItem("poketto:capture-space"),
    workspaceId,
  );
});

test("a signed-out browser is asked to sign in first", async (t) => {
  const f = await dom(t);
  globalThis.fetch = async () =>
    Response.json({ detail: "unauthorized" }, { status: 401 });
  const { CaptureForm } = await import("../components/capture-form");
  await f.act(async () =>
    f.root.render(<CaptureForm title="" url="" text="" />),
  );
  assert.match(f.container.textContent, /请先在这个浏览器登录 Poketto/);
});

test("owners can issue a capture-only key; writers get the bookmarklet; readers see nothing", async (t) => {
  const f = await dom(t);
  const posts: unknown[] = [];
  globalThis.fetch = async (input, options) => {
    if (String(input) === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    posts.push({
      path: String(input),
      body: JSON.parse(String(options?.body)),
    });
    return Response.json({ token: "pk_capture" });
  };
  const { CaptureSetup } = await import("../components/capture-setup");
  const root = scopedRoot(f.root);
  const owner = {
    accountId: "33333333-3333-4333-8333-333333333333",
    workspaceId,
    role: "OWNER" as const,
    capabilities: [],
  };
  await f.act(async () => root.render(<CaptureSetup identity={owner} />));
  assert.match(f.container.textContent, /iPhone 快捷指令/);
  assert.match(
    f.container.querySelector("textarea")!.value,
    /^javascript:.*https:\/\/site\.example\/capture\?/,
  );
  const button = Array.from(f.container.querySelectorAll("button")).find(
    (item) => item.textContent === "生成收集专用密钥",
  )!;
  await f.act(async () => button.click());
  assert.deepEqual(posts, [
    {
      path: `/api/admin/workspaces/${workspaceId}/keys`,
      body: { accountId: owner.accountId, capabilities: ["CAPTURE"] },
    },
  ]);
  assert.match(f.container.textContent, /收集密钥只显示这一次/);

  await f.act(async () =>
    root.render(
      <CaptureSetup
        identity={{ ...owner, role: "MEMBER", capabilities: ["WRITE_PRIVATE"] }}
      />,
    ),
  );
  assert.doesNotMatch(f.container.textContent, /iPhone 快捷指令/);
  assert.match(f.container.textContent, /电脑浏览器书签/);

  await f.act(async () =>
    root.render(
      <CaptureSetup
        identity={{ ...owner, role: "MEMBER", capabilities: ["READ_PRIVATE"] }}
      />,
    ),
  );
  assert.equal(f.container.textContent, "");
});
