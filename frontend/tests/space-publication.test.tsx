import assert from "node:assert/strict";
import test, { type TestContext } from "node:test";
import { Window } from "happy-dom";

async function fixture(t: TestContext) {
  const window = new Window({ url: "https://site.example/admin" });
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
  const { ConfirmationProvider } = await import("../components/confirmation");
  const { SpacePublication } = await import("../components/space-publication");
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
  const mount = async (id = "first") =>
    act(async () =>
      root.render(
        <ConfirmationProvider>
          <SpacePublication key={id} workspaceId={id} />
        </ConfirmationProvider>,
      ),
    );
  const click = async (label: string, dialog = false) =>
    act(async () => {
      const scope = dialog
        ? container.querySelector("dialog[open]")
        : container.querySelector("section");
      assert.ok(scope);
      const button = [...scope.querySelectorAll("button")].find(
        (value) => value.textContent === label,
      );
      assert.ok(button, label);
      button.click();
    });
  return { container, act, mount, click };
}

const publication = (workspaceId = "first", enabled = false) => ({
  workspaceId,
  enabled,
  eligible: true,
  effectiveEnabled: enabled,
  slug: workspaceId,
  displayName: workspaceId,
  publicAuthorName: "",
});

test("restricted websites retain the owner's switch while preventing publication", async (t) => {
  const f = await fixture(t);
  let enabled = true;
  globalThis.fetch = async (path, options) => {
    if (String(path).includes("/publication/restrictions?"))
      return Response.json({
        items: [{ ownerName: "作者", reason: "请修订公开内容" }],
        total: 1,
        offset: 0,
        limit: 30,
      });
    if (String(path) === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    if (options?.method === "PUT") {
      assert.deepEqual(JSON.parse(String(options.body)), { enabled: false });
      enabled = false;
    }
    return Response.json({
      ...publication("first", enabled),
      eligible: false,
      effectiveEnabled: false,
    });
  };
  await f.mount();
  assert.match(f.container.textContent, /公开展示已受限/);
  assert.match(f.container.textContent, /请修订公开内容/);
  assert.equal(f.container.querySelector("a"), null);
  await f.click("关闭公开网站");
  await f.click("关闭公开网站", true);
  const enable = [...f.container.querySelectorAll("button")].find(
    (button) => button.textContent === "开启公开网站",
  );
  assert.ok(enable?.disabled);
  assert.equal(enabled, false);
});

test("publication confirmation and authoritative acknowledgement control the visible state", async (t) => {
  const f = await fixture(t);
  let settle!: (value: Response) => void;
  let writes = 0;
  globalThis.fetch = async (path, options) => {
    if (String(path) === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    assert.equal(String(path), "/api/auth/workspaces/first/publication");
    if (options?.method === "PUT") {
      writes++;
      assert.equal(new Headers(options.headers).get("X-CSRF"), "fixture");
      assert.deepEqual(JSON.parse(String(options.body)), { enabled: true });
      return new Promise((resolve) => {
        settle = resolve;
      });
    }
    return Response.json(publication());
  };
  await f.mount();
  await f.click("开启公开网站");
  assert.equal(writes, 0);
  await f.click("取消", true);
  assert.equal(writes, 0);
  await f.click("开启公开网站");
  await f.click("开启公开网站", true);
  assert.equal(writes, 1);
  assert.equal(f.container.querySelector("strong")?.textContent, "已关闭");
  assert.equal(
    f.container.querySelector("section button")?.hasAttribute("disabled"),
    true,
  );
  await f.act(async () => settle(Response.json(publication("first", true))));
  assert.equal(f.container.querySelector("strong")?.textContent, "已开启");
  assert.equal(
    f.container.querySelector("a")?.getAttribute("href"),
    "/s/first",
  );
});

test("an ambiguous write requires rereading and never retains a false success badge", async (t) => {
  const f = await fixture(t);
  let writes = 0;
  let enabled = false;
  globalThis.fetch = async (path, options) => {
    if (String(path) === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    if (options?.method === "PUT") {
      writes++;
      enabled = true;
      throw new TypeError("lost response");
    }
    return Response.json(publication("first", enabled));
  };
  await f.mount();
  await f.click("开启公开网站");
  await f.click("开启公开网站", true);
  assert.equal(writes, 1);
  assert.equal(f.container.querySelector("strong"), null);
  assert.match(f.container.textContent, /未能确认网站当前状态/);
  await f.click("重新读取网站状态");
  assert.equal(writes, 1);
  assert.equal(f.container.querySelector("strong")?.textContent, "已开启");
});

test("switching spaces cancels an old confirmation and ignores an old read result", async (t) => {
  const f = await fixture(t);
  let writes = 0;
  let oldRead!: (value: Response) => void;
  globalThis.fetch = async (path, options) => {
    if (options?.method === "PUT") {
      writes++;
      return Response.json(publication("first", true));
    }
    if (String(path).includes("/slow/"))
      return new Promise((resolve) => {
        oldRead = resolve;
      });
    return Response.json(
      publication(String(path).includes("/second/") ? "second" : "first"),
    );
  };
  await f.mount();
  await f.click("开启公开网站");
  await f.mount("second");
  assert.equal(f.container.querySelector("dialog[open]"), null);
  assert.equal(writes, 0);
  await f.mount("slow");
  await f.mount("second");
  await f.act(async () => oldRead(Response.json(publication("slow", true))));
  assert.equal(f.container.querySelector("strong")?.textContent, "已关闭");
  assert.equal(f.container.querySelector("a"), null);
});

test("a profile save that fails part-way keeps what was saved and the unsaved edits", async (t) => {
  const f = await fixture(t);
  const writes: string[] = [];
  let current = { ...publication(), publicDescription: "" };
  globalThis.fetch = async (path, options) => {
    if (String(path) === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    if (options?.method === "PUT") {
      const field = String(path).split("/").at(-1)!;
      writes.push(field);
      if (field === "description")
        return Response.json({ detail: "简介暂时无法保存。" }, { status: 500 });
      const { text } = JSON.parse(String(options.body));
      current = { ...current, displayName: text };
      return Response.json(current);
    }
    return Response.json(current);
  };
  await f.mount();
  const type = async (selector: string, value: string) => {
    const field = f.container.querySelector(selector) as
      HTMLInputElement | HTMLTextAreaElement | null;
    assert.ok(field, selector);
    const prototype = Object.getPrototypeOf(field);
    await f.act(async () => {
      Object.getOwnPropertyDescriptor(prototype, "value")!.set!.call(
        field,
        value,
      );
      field.dispatchEvent(new window.Event("input", { bubbles: true }));
    });
  };
  const valueOf = (selector: string) =>
    (f.container.querySelector(selector) as unknown as HTMLInputElement).value;
  await type(".profile-editor input", "雨后的口袋");
  await type(".profile-editor textarea", "天桥与云");
  const form = f.container.querySelector(
    ".profile-editor form",
  ) as unknown as HTMLFormElement;
  await f.act(async () =>
    form.dispatchEvent(
      new window.Event("submit", { bubbles: true, cancelable: true }),
    ),
  );
  assert.deepEqual(writes, ["name", "description"]);
  const notice = f.container.querySelector(".profile-editor [role=alert]");
  assert.match(notice?.textContent ?? "", /空间名称已保存；空间简介没有保存/);
  assert.equal(valueOf(".profile-editor input"), "雨后的口袋");
  assert.equal(valueOf(".profile-editor textarea"), "天桥与云");
  assert.match(f.container.textContent, /网站开关/);
  const save = [...f.container.querySelectorAll("button")].find(
    (button) => button.textContent === "保存网站资料",
  );
  assert.equal(save?.disabled, false);
});
