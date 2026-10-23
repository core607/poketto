import assert from "node:assert/strict";
import test, { type TestContext } from "node:test";
import { Window } from "happy-dom";

async function fixture(t: TestContext, url: string) {
  const window = new Window({ url });
  const globals = {
    window,
    document: window.document,
    navigator: window.navigator,
    HTMLElement: window.HTMLElement,
    Event: window.Event,
    FormData: window.FormData,
    sessionStorage: window.sessionStorage,
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
  return { act, root, container };
}

const profile = (group: string) => ({
  account: {
    accountId: "reader",
    loginName: "reader",
    displayName: "Reader",
    siteAdministrator: group === "ADMINISTRATOR",
    group,
  },
});
const spaces = (total: number) =>
  Response.json({ items: [], total, offset: 0, limit: 1 });

test("the account menu offers the studio only to creators or members of a space", async (t) => {
  const f = await fixture(t, "https://site.example/");
  const { AccountEntrance } = await import("../components/site-chrome");
  for (const [group, total, studio] of [
    ["VIEWER", 0, false],
    ["COMMUNITY", 2, true],
    ["CREATOR", 0, true],
  ] as const) {
    const requests: string[] = [];
    globalThis.fetch = async (input) => {
      const path = String(input);
      requests.push(path);
      if (path === "/api/auth/account") return Response.json(profile(group));
      assert.equal(path, "/api/auth/workspaces?offset=0&limit=1");
      return spaces(total);
    };
    await f.act(async () => f.root.render(<AccountEntrance key={group} />));
    const links = [...f.container.querySelectorAll("a")].map((link) =>
      link.getAttribute("href"),
    );
    assert.equal(links.includes("/admin"), studio, group);
    assert.ok(links.includes("/account"), group);
    assert.ok(!links.some((link) => link?.includes("tab=account")), group);
    // Creators always qualify, so their group alone answers the question.
    assert.equal(requests.length, group === "CREATOR" ? 1 : 2, group);
  }
});

test("the sign-in link brings a reader back to the page they were reading", async (t) => {
  const f = await fixture(t, "https://site.example/s/notes/read/page?q=1");
  const { AccountEntrance } = await import("../components/site-chrome");
  globalThis.fetch = async () => new Response(null, { status: 401 });
  await f.act(async () => f.root.render(<AccountEntrance />));
  const link = f.container.querySelector("a.btn-primary");
  assert.equal(link?.textContent, "登录");
  assert.ok(link?.getAttribute("href")?.startsWith("/admin?next="));
});

test("the account page states what the group allows and keeps security settings", async (t) => {
  const f = await fixture(t, "https://site.example/account");
  const { AccountHome } = await import("../components/account-home");
  globalThis.fetch = async (input) => {
    const path = String(input);
    if (path === "/api/auth/account") return Response.json(profile("VIEWER"));
    if (path.startsWith("/api/auth/workspaces?")) return spaces(0);
    if (path === "/api/auth/identity/account")
      return Response.json({
        displayName: "Reader",
        email: "reader@example.com",
        passwordEnabled: true,
        googleEmail: null,
      });
    if (path === "/api/auth/identity/policy")
      return Response.json({ emailAvailable: true, googleAvailable: false });
    assert.fail("Unexpected request " + path);
  };
  await f.act(async () => f.root.render(<AccountHome />));
  assert.match(f.container.textContent, /账号类型：浏览者/);
  assert.match(f.container.textContent, /需要站点管理员授予创作者资格/);
  assert.match(f.container.textContent, /登录与安全/);
  assert.ok(f.container.querySelector("#creator"));
  assert.equal(f.container.querySelector('a[href="/admin"]'), null);
});
