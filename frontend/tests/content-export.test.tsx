import { scopedRoot } from "./workspace-fixture";
import assert from "node:assert/strict";
import test from "node:test";
import { Window } from "happy-dom";

test("export dialog uses scoped CSRF requests, native downloads, and safe cancellation", async (t) => {
  const window = new Window({ url: "http://localhost/admin" });
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
  const { ExportDialog } = await import("../components/export-dialog");
  const trigger = window.document.createElement("button");
  trigger.textContent = "导出";
  const container = window.document.createElement("div");
  window.document.body.append(trigger, container);
  const root = scopedRoot(createRoot(container as unknown as HTMLDivElement));
  const oldFetch = globalThis.fetch;
  const requests: { paths: string[]; publicOnly: boolean }[] = [];
  const releases: string[] = [];
  let response: () => Response | Promise<Response> = () =>
    Response.json(receipt);
  const receipt = {
    handle: "handle-1",
    publicOnly: false,
    bytes: 2048,
    sha256: "a".repeat(64),
    expiresAt: new Date(Date.now() + 600000).toISOString(),
  };
  globalThis.fetch = async (input, options) => {
    const path = String(input);
    if (path === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    assert.equal(options?.method, "POST");
    assert.equal(new Headers(options.headers).get("X-CSRF"), "fixture");
    if (path.endsWith("/release")) {
      releases.push(path);
      return new Response(null, { status: 204 });
    }
    assert.equal(
      path,
      "/api/admin/workspaces/11111111-1111-4111-8111-111111111111/exports",
    );
    requests.push(JSON.parse(String(options.body)));
    return response();
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
  const open = async () => {
    trigger.focus();
    await act(async () =>
      root.render(
        <ExportDialog
          source="private/note.md"
          returnFocus={trigger as unknown as HTMLElement}
          fallbackFocus={null}
          onClose={() => root.render(null)}
        />,
      ),
    );
  };
  const button = (label: string) => {
    const found = [...container.querySelectorAll("button")].find(
      (element) => element.textContent === label,
    );
    assert.ok(found, label);
    return found;
  };
  await open();
  await act(async () =>
    container
      .querySelector("dialog")!
      .dispatchEvent(new window.Event("cancel", { cancelable: true })),
  );
  assert.equal(requests.length, 0);
  assert.equal(window.document.activeElement, trigger);

  await open();
  await act(async () => button("生成 ZIP").click());
  assert.deepEqual(requests, [
    { paths: ["private/note.md"], publicOnly: false },
  ]);
  const download = container.querySelector("a")!;
  assert.equal(
    download.getAttribute("href"),
    "/api/admin/workspaces/11111111-1111-4111-8111-111111111111/exports/handle-1",
  );
  assert.equal(download.hasAttribute("download"), true);
  await act(async () => button("完成").click());
  assert.equal(releases.length, 0);
  assert.equal(window.document.activeElement, trigger);

  await open();
  response = () => new Response(null, { status: 503 });
  await act(async () => button("生成 ZIP").click());
  assert.equal(requests.at(-1)?.publicOnly, false);
  assert.match(
    container.querySelector('[role="alert"]')!.textContent!,
    /文档或媒体索引中的附件/,
  );
  assert.doesNotMatch(
    container.querySelector('[role="alert"]')!.textContent!,
    /已公开/,
  );
  await act(async () => container.querySelectorAll("input")[1].click());
  await act(async () => button("生成 ZIP").click());
  assert.equal(requests.at(-1)?.publicOnly, true);
  assert.match(
    container.querySelector('[role="alert"]')!.textContent!,
    /文件及附件均已公开/,
  );
  assert.equal(container.querySelector("a"), null);
  await act(async () => button("取消").click());

  await open();
  let finish!: (result: Response) => void;
  response = () =>
    new Promise<Response>((resolve) => {
      finish = resolve;
    });
  const before = requests.length;
  await act(async () => {
    button("生成 ZIP").click();
    button("生成 ZIP").click();
  });
  assert.equal(requests.length, before + 1);
  await act(async () => button("取消").click());
  await act(async () =>
    finish(Response.json({ ...receipt, handle: "unused" })),
  );
  assert.deepEqual(releases, [
    "/api/admin/workspaces/11111111-1111-4111-8111-111111111111/exports/unused/release",
  ]);
  assert.equal(container.querySelector("dialog"), null);
  assert.equal(window.document.activeElement, trigger);
});
