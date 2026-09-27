import { scopedRoot } from "./workspace-fixture";
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
    HTMLInputElement: window.HTMLInputElement,
    sessionStorage: window.sessionStorage,
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
  const { Admin } = await import("../components/admin");
  const { Login } = await import("../components/login");
  const container = window.document.createElement("div");
  window.document.body.append(container);
  const root = scopedRoot(createRoot(container as unknown as HTMLDivElement));
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
    displayName: "fixture-member",
    siteAdministrator: false,
    group: "VIEWER" as const,
  },
};
const page = { items: [], total: 0, offset: 0, limit: 30 };

test("Google login preserves the connection return URL and excludes the fragment from authorization", async (t) => {
  const f = await fixture(
    t,
    "https://site.example/connect?client_id=fixture&state=caller#private-fragment",
  );
  let sent: unknown;
  globalThis.fetch = async (input, options) => {
    const path = String(input);
    if (path === "/api/auth/identity/policy")
      return Response.json({ emailAvailable: false, googleAvailable: true });
    if (path === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    assert.equal(path, "/api/auth/identity/google/start");
    assert.equal(options?.method, "POST");
    sent = JSON.parse(String(options?.body));
    return Response.json({ url: "https://accounts.example.test/authorize" });
  };
  await f.act(async () =>
    f.root.render(<f.Login connection onLogin={async () => {}} />),
  );
  assert.doesNotMatch(f.container.textContent, /忘记密码|注册/);
  await f.act(async () => f.button("使用 Google 登录").click());
  assert.deepEqual(sent, {
    mode: "LOGIN",
    returnTo: "/connect?client_id=fixture&state=caller",
  });
  assert.equal(
    f.window.location.href,
    "https://accounts.example.test/authorize",
  );
});

test("a Google email collision explains linking and removes only its own callback status", async (t) => {
  const f = await fixture(
    t,
    "https://site.example/admin?tab=account&loginError=google_email_in_use",
  );
  globalThis.fetch = async () =>
    Response.json({ emailAvailable: true, googleAvailable: true });
  await f.act(async () => f.root.render(<f.Login onLogin={async () => {}} />));
  assert.match(f.container.textContent, /原有方式登录/);
  assert.equal(f.window.location.search, "?tab=account");
});

test("Google-only accounts cannot remove their only login and server rejection never claims unlink success", async (t) => {
  const f = await fixture(t);
  const { AccountSecurity } = await import("../components/account-security");
  let passwordEnabled = false,
    removals = 0;
  globalThis.fetch = async (input, options) => {
    const path = String(input);
    if (path === "/api/auth/identity/policy")
      return Response.json({ emailAvailable: true, googleAvailable: true });
    if (path === "/api/auth/identity/account")
      return Response.json({
        displayName: "Reader",
        email: "reader@example.com",
        googleEmail: "reader@example.com",
        passwordEnabled,
      });
    if (path === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    assert.equal(path, "/api/auth/identity/google");
    assert.equal(options?.method, "DELETE");
    removals++;
    return Response.json({ code: "LAST_LOGIN_METHOD" }, { status: 409 });
  };
  await f.act(async () => f.root.render(<AccountSecurity />));
  assert.equal(f.button("解除 Google 绑定").disabled, true);
  assert.match(f.container.textContent, /唯一的登录方式/);
  passwordEnabled = true;
  await f.act(async () =>
    f.root.render(<AccountSecurity key="password-added" />),
  );
  await f.act(async () => f.button("解除 Google 绑定").click());
  assert.equal(removals, 1);
  assert.match(f.container.textContent, /至少需要保留一种登录方式/);
  assert.doesNotMatch(f.container.textContent, /绑定已解除/);
  assert.match(f.container.textContent, /Google：reader@example.com/);
});

test("an existing account can bind a verified email and retains its profile after a collision", async (t) => {
  const f = await fixture(t);
  const { AccountSecurity } = await import("../components/account-security");
  const profile = {
    displayName: "Existing reader",
    email: null,
    passwordEnabled: true,
    googleEmail: null,
  };
  let attempts = 0;
  globalThis.fetch = async (input, options) => {
    const path = String(input);
    const read = identityRead(path);
    if (read) return read;
    if (path === "/api/auth/identity/account") return Response.json(profile);
    if (path === "/api/auth/identity/email/challenge")
      return Response.json(receipt());
    assert.equal(path, "/api/auth/identity/email");
    assert.equal(options?.method, "PUT");
    assert.deepEqual(JSON.parse(String(options?.body)), {
      challengeId: "challenge-fixture",
      email: "reader@example.com",
      code: "123456",
    });
    return ++attempts === 1
      ? Response.json({ code: "EMAIL_IN_USE" }, { status: 409 })
      : Response.json({ ...profile, email: "reader@example.com" });
  };
  await f.act(async () => f.root.render(<AccountSecurity />));
  await f.act(async () => f.button("绑定邮箱").click());
  f.input("email", "reader@example.com");
  await f.act(async () => f.button("获取验证码").click());
  f.input("code", "123456");
  const bindingForm = f.container
    .querySelector('input[name="email"]')!
    .closest("form")!;
  const submitBinding = () =>
    f.act(async () => {
      bindingForm.dispatchEvent(
        new f.window.Event("submit", { bubbles: true, cancelable: true }),
      );
    });
  await submitBinding();
  assert.match(f.container.textContent, /该邮箱已绑定账号/);
  assert.equal(
    f.container.querySelector<HTMLInputElement>('input[name="displayName"]')
      ?.value,
    "Existing reader",
  );
  assert.equal(
    f.container.querySelector<HTMLInputElement>('input[name="email"]')?.value,
    "reader@example.com",
  );
  await submitBinding();
  assert.match(f.container.textContent, /邮箱已验证并绑定/);
  assert.match(f.container.textContent, /邮箱：reader@example.com/);
  assert.equal(f.container.querySelector('input[name="code"]'), null);
});

const receipt = () => ({
  challengeId: "challenge-fixture",
  expiresAt: new Date(Date.now() + 600000).toISOString(),
  retryAfterSeconds: 60,
});
function identityRead(path: string) {
  if (path === "/api/auth/identity/policy")
    return Response.json({ emailAvailable: true });
  if (path === "/api/auth/csrf")
    return Response.json({ headerName: "X-CSRF", token: "fixture" });
}

test("email signup verifies the requested mailbox and a failed automatic login does not redeem twice", async (t) => {
  const f = await fixture(t);
  const writes: string[] = [];
  let logins = 0,
    ready = 0;
  globalThis.fetch = async (input, options) => {
    const path = String(input);
    const read = identityRead(path);
    if (read) return read;
    writes.push(path);
    assert.equal(new Headers(options?.headers).get("X-CSRF"), "fixture");
    if (path === "/api/auth/identity/signup/challenge") {
      assert.deepEqual(JSON.parse(String(options?.body)), {
        email: "reader@example.com",
      });
      return Response.json(receipt());
    }
    if (path === "/api/auth/identity/signup") {
      assert.deepEqual(JSON.parse(String(options?.body)), {
        proof: {
          challengeId: "challenge-fixture",
          email: "reader@example.com",
          code: "123456",
        },
        password: "fixture-password-long",
        displayName: "Reader",
      });
      return Response.json({ accountId: "new-member" }, { status: 201 });
    }
    assert.equal(path, "/api/auth/login");
    assert.equal(
      new URLSearchParams(String(options?.body)).get("username"),
      "reader@example.com",
    );
    return new Response(null, { status: ++logins === 1 ? 401 : 204 });
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
  await f.act(async () => f.button("注册").click());
  assert.equal(f.container.querySelector('input[name="token"]'), null);
  f.input("email", "reader@example.com");
  await f.act(async () => f.button("获取验证码").click());
  assert.equal(f.button("60 秒后可重发").disabled, true);
  f.input("displayName", "Reader");
  f.input("code", "123456");
  f.input("password", "fixture-password-long");
  f.input("confirmation", "fixture-password-long");
  await f.submit();
  assert.match(f.container.textContent, /账号已创建/);
  assert.equal(
    f.container.querySelector<HTMLInputElement>('input[name="login"]')?.value,
    "reader@example.com",
  );
  f.input("password", "fixture-password-long");
  await f.submit();
  assert.deepEqual(writes, [
    "/api/auth/identity/signup/challenge",
    "/api/auth/identity/signup",
    "/api/auth/login",
    "/api/auth/login",
  ]);
  assert.equal(ready, 1);
  assert.equal(f.window.sessionStorage.length, 0);
});

test("editing the mailbox after delivery cannot submit the previous email proof", async (t) => {
  const f = await fixture(t);
  const writes: string[] = [];
  globalThis.fetch = async (input) => {
    const path = String(input);
    const read = identityRead(path);
    if (read) return read;
    writes.push(path);
    assert.equal(path, "/api/auth/identity/signup/challenge");
    return Response.json(receipt());
  };
  await f.act(async () => f.root.render(<f.Login onLogin={async () => {}} />));
  await f.act(async () => f.button("注册").click());
  f.input("email", "first@example.com");
  await f.act(async () => f.button("获取验证码").click());
  f.input("email", "second@example.com");
  f.input("displayName", "Reader");
  f.input("code", "123456");
  f.input("password", "fixture-password-long");
  f.input("confirmation", "fixture-password-long");
  await f.submit();
  assert.match(f.container.textContent, /请先为当前邮箱获取验证码/);
  assert.deepEqual(writes, ["/api/auth/identity/signup/challenge"]);
});

test("recovery gives a uniform delivery message, preserves input after rejection, and returns to login on success", async (t) => {
  const f = await fixture(t);
  let attempts = 0;
  globalThis.fetch = async (input, options) => {
    const path = String(input);
    const read = identityRead(path);
    if (read) return read;
    if (path === "/api/auth/identity/recovery/challenge")
      return Response.json(receipt());
    assert.equal(path, "/api/auth/identity/recovery");
    assert.equal(
      JSON.parse(String(options?.body)).proof.email,
      "reader@example.com",
    );
    return ++attempts === 1
      ? Response.json({ code: "INVALID_CHALLENGE" }, { status: 400 })
      : new Response(null, { status: 204 });
  };
  await f.act(async () =>
    f.root.render(
      <f.Login
        onLogin={async () => assert.fail("Recovery must require a fresh login")}
      />,
    ),
  );
  await f.act(async () => f.button("忘记密码").click());
  f.input("email", "reader@example.com");
  await f.act(async () => f.button("获取验证码").click());
  assert.match(f.container.textContent, /若该邮箱已绑定账号/);
  f.input("code", "000000");
  f.input("password", "fixture-password-long");
  f.input("confirmation", "fixture-password-long");
  await f.submit();
  assert.match(f.container.textContent, /验证码无效/);
  assert.equal(
    f.container.querySelector<HTMLInputElement>('input[name="email"]')?.value,
    "reader@example.com",
  );
  f.input("code", "123456");
  await f.submit();
  assert.match(f.container.textContent, /旧会话和旧连接密钥已失效/);
  assert.equal(
    f.container.querySelector<HTMLInputElement>('input[name="login"]')?.value,
    "reader@example.com",
  );
  assert.equal(
    f.container.querySelector<HTMLInputElement>('input[name="password"]')
      ?.value,
    "",
  );
});

test("a rejected logout preserves the signed-in account and its navigation", async (t) => {
  const f = await fixture(t);
  globalThis.fetch = async (input) => {
    const path = new URL(String(input), "https://site.example").pathname;
    if (path === "/api/auth/account") return Response.json(account);
    if (
      path === "/api/auth/workspaces" ||
      path === "/api/auth/registration-invitations"
    )
      return Response.json(page);
    if (path === "/api/auth/workspaces/creation-policy")
      return Response.json({ available: false, eligible: true });
    if (path === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    if (path === "/api/auth/logout") return new Response(null, { status: 503 });
    assert.fail("Unexpected request: " + path);
  };
  await f.act(async () => f.root.render(<f.Admin />));
  const navigation = f.window.location.href;
  await f.act(async () => f.button("退出登录").click());
  assert.equal(f.window.location.href, navigation);
  assert.ok(f.button("退出登录"));
  assert.equal(f.container.querySelector('input[name="password"]'), null);
  assert.match(f.container.textContent, /服务暂时不可用/);
});

test("workspace connection retries reuse the request and never persist the provider token", async (t) => {
  const f = await fixture(t);
  const { CreateWorkspace } = await import("../components/create-workspace");
  const requests: Record<string, string>[] = [];
  let opened = "";
  globalThis.fetch = async (input, options) => {
    const path = String(input);
    if (path === "/api/auth/workspaces/creation-policy")
      return Response.json({ available: true, eligible: true });
    if (path === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    assert.equal(path, "/api/auth/workspaces/creations");
    requests.push(JSON.parse(String(options?.body)));
    return requests.length === 1
      ? new Response(null, { status: 503 })
      : Response.json({
          workspaceId: "11111111-1111-4111-8111-111111111111",
          stage: "READY",
          retryAfterSeconds: 0,
        });
  };
  await f.act(async () =>
    f.root.render(
      <CreateWorkspace
        accountId="fixture"
        onCreated={async (id) => {
          opened = id;
        }}
      />,
    ),
  );
  f.input("displayName", "Reading room");
  f.input("slug", "reading-room");
  f.input("repository", "https://cnb.cool/example/reading");
  f.input("token", "secret-provider-fixture");
  await f.submit();
  const stored = f.window.sessionStorage.getItem(
    "poketto.workspace-creation.fixture",
  );
  assert.ok(stored);
  assert.doesNotMatch(stored, /secret-provider-fixture|token|username/);
  assert.equal(
    f.container.querySelector<HTMLInputElement>('input[name="repository"]')
      ?.readOnly,
    true,
  );
  await f.submit();
  assert.equal(requests.length, 2);
  assert.equal(requests[0].requestId, requests[1].requestId);
  assert.match(f.container.textContent, /空间已创建，公开网站尚未开启/);
  assert.equal(opened, "");
  await f.act(async () => f.button("打开空间").click());
  assert.equal(opened, "11111111-1111-4111-8111-111111111111");
  assert.equal(
    f.window.sessionStorage.getItem("poketto.workspace-creation.fixture"),
    null,
  );
});

test("a no-space account stays signed in and joins explicitly from account management", async (t) => {
  const f = await fixture(t);
  let joined = false;
  const writes: string[] = [];
  globalThis.fetch = async (input, options) => {
    const path = new URL(String(input), "https://site.example").pathname;
    if (path === "/api/auth/workspaces/creation-policy")
      return Response.json({ available: false, eligible: true });
    if (path === "/api/auth/account") return Response.json(account);
    if (path === "/api/auth/workspaces")
      return Response.json({
        ...page,
        total: joined ? 1 : 0,
        items: joined
          ? [
              {
                workspaceId: "11111111-1111-4111-8111-111111111111",
                displayName: "Joined space",
                role: "MEMBER",
                capabilities: [],
              },
            ]
          : [],
      });
    if (path === "/api/auth/workspaces/11111111-1111-4111-8111-111111111111/me")
      return joined
        ? Response.json({
            accountId: "fixture-member",
            workspaceId: "11111111-1111-4111-8111-111111111111",
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
      return Response.json({
        workspaceId: "11111111-1111-4111-8111-111111111111",
      });
    }
    if (
      path ===
      "/api/admin/workspaces/11111111-1111-4111-8111-111111111111/repository/tree"
    )
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
    if (path === "/api/auth/workspaces/creation-policy")
      return Response.json({ available: false, eligible: true });
    if (path === "/api/auth/account") return Response.json(account);
    if (path === "/api/auth/workspaces")
      return new Response(null, { status: 503 });
    if (path === "/api/auth/workspaces/11111111-1111-4111-8111-111111111111/me")
      return new Response(null, { status: 503 });
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
