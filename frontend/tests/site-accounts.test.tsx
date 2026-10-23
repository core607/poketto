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
      items:
        path.includes("group-history") || path.includes("/workspaces")
          ? []
          : [
              {
                accountId: "operator",
                loginName: "operator",
                displayName: "operator",
                group: "ADMINISTRATOR",
                ownedSpaces: 2,
                createdAt: "2026-10-01T00:00:00Z",
              },
            ],
      total:
        path.includes("group-history") || path.includes("/workspaces") ? 0 : 1,
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

test("site administration filters accounts by group and opens counted lists from the overview", async (t) => {
  const window = new Window({ url: "https://site.example/admin?tab=site" });
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
  const { SiteAdministration } =
    await import("../components/site-administration");
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
  const paths: string[] = [];
  globalThis.fetch = async (input) => {
    const path = String(input);
    paths.push(path);
    if (path === "/api/auth/site/overview")
      return Response.json({
        accounts: { VIEWER: 7, COMMUNITY: 2, CREATOR: 3, ADMINISTRATOR: 1 },
        newAccounts: 4,
        spaces: 5,
        publicSpaces: 2,
        restrictedSpaces: 1,
        openReports: 6,
      });
    const items = path.startsWith("/api/auth/site/workspaces?")
      ? [
          {
            workspaceId: "space",
            slug: "notes",
            displayName: "Notes",
            enabled: true,
            eligible: false,
            createdAt: "2026-10-01T00:00:00Z",
            owners: ["Ann", "Bo", "Cy"],
            ownerCount: 4,
          },
        ]
      : [];
    return Response.json({ items, total: items.length, offset: 0, limit: 30 });
  };
  const account = {
    account: {
      accountId: "operator",
      loginName: "operator",
      displayName: "Operator",
      siteAdministrator: true,
      group: "ADMINISTRATOR" as const,
    },
  };
  const button = (text: string) => {
    const found = [...container.querySelectorAll("button")].find((item) =>
      item.textContent?.startsWith(text),
    );
    assert.ok(found, "Missing button " + text);
    return found;
  };
  await act(async () => root.render(<SiteAdministration account={account} />));
  assert.match(container.textContent, /全部账号13/);
  assert.match(container.textContent, /未处理举报6/);
  await act(async () => button("展示受限的空间").click());
  assert.equal(
    paths.at(-1),
    "/api/auth/site/workspaces?state=RESTRICTED&query=&offset=0&limit=30",
  );
  assert.match(
    container.textContent,
    /\/s\/notes · 所有者：Ann、Bo、Cy 等 4 人/,
  );
  assert.match(container.textContent, /展示受限/);
  await act(async () => button("概览").click());
  await act(async () => button("近 7 天新注册").click());
  assert.equal(
    paths.at(-1),
    "/api/auth/site/accounts?query=&order=NEWEST&offset=0&limit=30",
  );
  await act(async () => button("创作者").click());
  assert.equal(
    paths.at(-1),
    "/api/auth/site/accounts?query=&group=CREATOR&order=NEWEST&offset=0&limit=30",
  );
  assert.equal(button("创作者").getAttribute("aria-pressed"), "true");
});
