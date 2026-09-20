import assert from "node:assert/strict";
import test from "node:test";
import { Window } from "happy-dom";

test("site policy editor describes withdrawal, requires a reason and handles last-admin refusal", async (t) => {
  const window = new Window({ url: "https://site.example/admin" });
  const globals = {
    window,
    document: window.document,
    navigator: window.navigator,
    HTMLElement: window.HTMLElement,
    Event: window.Event,
    FormData: window.FormData,
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
  const { SiteAccounts } = await import("../components/site-accounts");
  const container = window.document.createElement("div");
  window.document.body.append(container);
  const root = createRoot(container as unknown as HTMLDivElement);
  const oldFetch = globalThis.fetch;
  t.after(async () => {
    await act(async () => root.unmount());
    globalThis.fetch = oldFetch;
    await window.happyDOM.close();
    for (const [name, descriptor] of previous) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else Reflect.deleteProperty(globalThis, name);
    }
  });
  let writes = 0;
  globalThis.fetch = async (input, options) => {
    const path = String(input);
    if (path === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    if (options?.method === "PUT") {
      assert.equal(path, "/api/auth/site/accounts/operator/group");
      assert.deepEqual(JSON.parse(String(options.body)), {
        group: "VIEWER",
        reason: "Requested retirement",
      });
      writes++;
      return Response.json({ code: "LAST_ADMINISTRATOR" }, { status: 409 });
    }
    return Response.json({
      items: path.includes("group-history")
        ? []
        : [
            {
              accountId: "operator",
              loginName: "operator",
              group: "ADMINISTRATOR",
              ownedSpaces: 2,
            },
          ],
      total: path.includes("group-history") ? 0 : 1,
      offset: 0,
      limit: 30,
    });
  };
  await act(async () => root.render(<SiteAccounts />));
  const manage = [...container.querySelectorAll("button")].find(
    (button) => button.textContent === "管理 operator",
  );
  assert.ok(manage);
  await act(async () => manage.click());
  const section = container.querySelector(
    'section[aria-label="operator 的策略组"]',
  );
  assert.ok(section);
  const select = section.querySelector("select");
  assert.ok(select);
  await act(async () => {
    select.value = "VIEWER";
    select.dispatchEvent(new window.Event("change", { bubbles: true }));
  });
  assert.match(section.textContent, /2 个空间将停止公开展示/);
  assert.match(section.textContent, /编辑和成员权限保留/);
  const reason = section.querySelector("textarea");
  assert.ok(reason?.required);
  reason.value = "Requested retirement";
  await act(async () => {
    section
      .querySelector("form")!
      .dispatchEvent(
        new window.Event("submit", { bubbles: true, cancelable: true }),
      );
  });
  assert.equal(writes, 1);
  assert.match(section.textContent, /至少需要保留一位站点管理员/);
});
