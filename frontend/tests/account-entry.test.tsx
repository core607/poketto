import assert from "node:assert/strict";
import test, { type TestContext } from "node:test";
import { Window, type HTMLInputElement } from "happy-dom";

async function fixture(t: TestContext, url = "https://site.example/admin") {
  const window = new Window({ url });
  const globals = {
    window,
    document: window.document,
    navigator: window.navigator,
    HTMLElement: window.HTMLElement,
    HTMLDialogElement: window.HTMLDialogElement,
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
  const { Admin, Login } = await import("../components/admin");
  const container = window.document.createElement("div");
  window.document.body.append(container);
  const root = createRoot(container as unknown as HTMLDivElement);
  const previousFetch = globalThis.fetch;
  t.after(async () => {
    await act(async () => root.unmount());
    globalThis.fetch = previousFetch;
    await window.happyDOM.close();
    for (const [name, value] of previous) {
      if (value) Object.defineProperty(globalThis, name, value);
      else Reflect.deleteProperty(globalThis, name);
    }
  });
  const button = (name: string) => {
    const result = [...container.querySelectorAll("button")].find(
      (value) => value.textContent === name,
    );
    assert.ok(result, "Missing button: " + name);
    return result;
  };
  const submit = async () => {
    const form = container.querySelector("form");
    assert.ok(form);
    await act(async () => {
      form.dispatchEvent(
        new window.Event("submit", { bubbles: true, cancelable: true }),
      );
    });
  };
  const input = (name: string, value: string) => {
    const field = container.querySelector<HTMLInputElement>(
      'input[name="' + name + '"]',
    );
    assert.ok(field);
    field.value = value;
  };
  return { window, act, root, container, Admin, Login, button, submit, input };
}
const account = {
  account: {
    accountId: "fixture-member",
    loginName: "fixture-member",
    siteAdministrator: false,
  },
  mayIssueRegistrationInvitations: false,
};
const page = { items: [], total: 0, offset: 0, limit: 30 };

test("registration links populate the code without sending it; a login retry does not redeem it again", async (t) => {
  const f = await fixture(
    t,
    "https://site.example/admin#register=registration_fixture",
  );
  const writes: string[] = [];
  let logins = 0,
    ready = 0;
  globalThis.fetch = async (input, options) => {
    const path = String(input);
    if (path === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    assert.equal(options?.method, "POST");
    assert.equal(new Headers(options.headers).get("X-CSRF"), "fixture");
    writes.push(path);
    if (path === "/api/auth/register") {
      const body = JSON.parse(String(options?.body));
      assert.equal(body.token, "registration_fixture");
      assert.equal(body.login, "new-member");
      return Response.json({ accountId: "new-member" }, { status: 201 });
    }
    assert.equal(path, "/api/auth/login");
    logins++;
    return new Response(null, { status: logins === 1 ? 401 : 204 });
  };
  await f.act(async () =>
    f.root.render(
      <f.Login
        onLogin={async () => {
          ready++;
        }}
      />,
    ),
  );
  assert.equal(f.window.location.hash, "");
  assert.equal(
    f.container.querySelector<HTMLInputElement>('input[name="token"]')?.value,
    "registration_fixture",
  );
  assert.deepEqual(writes, []);
  assert.doesNotMatch(f.container.textContent, /接受邀请|首次初始化/);
  f.input("login", "new-member");
  f.input("password", "fixture-password-long");
  f.input("confirmation", "fixture-password-long");
  await f.submit();
  assert.deepEqual(writes, ["/api/auth/register", "/api/auth/login"]);
  assert.match(f.container.textContent, /账号已创建/);
  assert.equal(
    f.container.querySelector<HTMLInputElement>('input[name="login"]')?.value,
    "new-member",
  );
  f.input("password", "fixture-password-long");
  await f.submit();
  assert.deepEqual(writes, [
    "/api/auth/register",
    "/api/auth/login",
    "/api/auth/login",
  ]);
  assert.equal(ready, 1);
});

test("a no-space account stays signed in and joins explicitly from account management", async (t) => {
  const f = await fixture(t);
  let joined = false;
  const writes: string[] = [];
  globalThis.fetch = async (input, options) => {
    const path = new URL(String(input), "https://site.example").pathname;
    if (path === "/api/auth/account") return Response.json(account);
    if (path === "/api/auth/me")
      return joined
        ? Response.json({
            accountId: "fixture-member",
            workspaceId: "fixture",
            role: "MEMBER",
            capabilities: [],
          })
        : new Response(null, { status: 403 });
    if (path === "/api/auth/registration-invitations")
      return Response.json(page);
    if (path === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    if (path === "/api/auth/invitations/accept") {
      writes.push(path);
      assert.equal(
        JSON.parse(String(options?.body)).token,
        "workspace_fixture",
      );
      joined = true;
      return Response.json({ workspaceId: "fixture" });
    }
    if (path === "/api/admin/repository/tree")
      return Response.json({ commit: "fixture", entries: [], diagnostics: [] });
    assert.fail("Unexpected request: " + path);
  };
  await f.act(async () => f.root.render(<f.Admin />));
  assert.match(f.container.textContent, /你已登录，还没有可访问的空间/);
  assert.ok(f.button("退出登录"));
  assert.equal(
    [...f.container.querySelectorAll("button")].some(
      (b) => b.textContent === "创建注册邀请码",
    ),
    false,
  );
  assert.deepEqual(writes, []);
  f.input("token", "workspace_fixture");
  await f.submit();
  assert.deepEqual(writes, ["/api/auth/invitations/accept"]);
  assert.ok(f.button("内容"));
  assert.doesNotMatch(f.container.textContent, /还没有可访问的空间/);
});

test("an unavailable workspace does not turn a verified account into a login form", async (t) => {
  const f = await fixture(t);
  globalThis.fetch = async (input) => {
    const path = new URL(String(input), "https://site.example").pathname;
    if (path === "/api/auth/account") return Response.json(account);
    if (path === "/api/auth/me") return new Response(null, { status: 503 });
    if (path === "/api/auth/registration-invitations")
      return Response.json(page);
    assert.fail("Unexpected request: " + path);
  };
  await f.act(async () => f.root.render(<f.Admin />));
  assert.ok(f.button("退出登录"));
  assert.ok(f.button("重新读取空间"));
  assert.match(f.container.textContent, /账号仍已登录/);
  assert.doesNotMatch(f.container.textContent, /还没有可访问的空间/);
  assert.equal(f.container.querySelector('input[name="password"]'), null);
});
