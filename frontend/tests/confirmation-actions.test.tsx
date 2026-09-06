import assert from "node:assert/strict";
import test, { type TestContext } from "node:test";
import { Window } from "happy-dom";

async function fixture(t: TestContext) {
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
  for (const [name, value] of Object.entries(globals)) {
    Object.defineProperty(globalThis, name, {
      configurable: true,
      writable: true,
      value,
    });
  }
  const { act } = await import("react");
  const { createRoot } = await import("react-dom/client");
  const { ConfirmationProvider, useConfirmation } =
    await import("../components/confirmation");
  const container = window.document.createElement("div");
  window.document.body.append(container);
  const root = createRoot(container as unknown as HTMLDivElement);
  const previousFetch = globalThis.fetch;
  const writes: { path: string; method: string; body: unknown }[] = [];
  const member = {
    accountId: "member",
    loginName: "fixture-member",
    role: "MEMBER",
    active: true,
  };
  const key = {
    id: "fixture-key",
    accountId: member.accountId,
    capabilities: ["READ_PRIVATE", "WRITE_PRIVATE"],
    revoked: false,
  };
  let rejectWrite = false;
  globalThis.fetch = async (input, options) => {
    const path = new URL(String(input), "http://localhost").pathname;
    const method = options?.method ?? "GET";
    if (path === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    if (method === "GET") {
      const items =
        path === "/api/admin/members"
          ? [member]
          : path === "/api/admin/keys"
            ? [key]
            : path === "/api/admin/invitations"
              ? []
              : undefined;
      assert.ok(items, `Unexpected read: ${path}`);
      return Response.json({
        items,
        total: items.length,
        offset: 0,
        limit: 30,
      });
    }
    assert.ok(
      (path === "/api/admin/members/member" && method === "PUT") ||
        (path === "/api/admin/keys/fixture-key" && method === "DELETE"),
      `Unexpected mutation: ${method} ${path}`,
    );
    assert.equal(new Headers(options?.headers).get("X-CSRF"), "fixture");
    writes.push({
      path,
      method,
      body: options?.body ? JSON.parse(String(options.body)) : undefined,
    });
    if (rejectWrite) return new Response(null, { status: 409 });
    if (method === "PUT") member.active = false;
    else key.revoked = true;
    return new Response(null, { status: 204 });
  };
  t.after(async () => {
    await act(async () => root.unmount());
    globalThis.fetch = previousFetch;
    await window.happyDOM.close();
    for (const [name, descriptor] of previous) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else Reflect.deleteProperty(globalThis, name);
    }
  });
  const button = (
    label: string,
    scope: { querySelectorAll: typeof container.querySelectorAll } = container,
  ) => {
    const value = [...scope.querySelectorAll("button")].find(
      (item) => item.textContent?.trim() === label,
    );
    assert.ok(value, `Missing button: ${label}`);
    return value;
  };
  const dialog = () => {
    const value = window.document.querySelector("dialog[open]");
    assert.ok(value, "An actual open DOM dialog is required before mutation");
    return value;
  };
  return {
    window,
    container,
    root,
    act,
    button,
    dialog,
    writes,
    ConfirmationProvider,
    useConfirmation,
    rejectWrites: () => {
      rejectWrite = true;
    },
  };
}

for (const kind of ["members", "keys"] as const) {
  async function mount(t: TestContext) {
    const f = await fixture(t);
    const { Members } = await import("../components/members");
    const { Keys } = await import("../components/keys");
    await f.act(async () =>
      f.root.render(
        <f.ConfirmationProvider>
          {kind === "members" ? (
            <Members />
          ) : (
            <Keys
              identity={{
                accountId: "member",
                workspaceId: "workspace",
                role: "OWNER",
                capabilities: ["MANAGE_KEYS"],
              }}
            />
          )}
        </f.ConfirmationProvider>,
      ),
    );
    return f;
  }

  const action = kind === "members" ? "停用" : "撤销";
  test(`${kind}: pending, cancel and native cancel event never write; confirmation writes exactly once`, async (t) => {
    const f = await mount(t);
    const trigger = f.button(action);
    trigger.focus();
    await f.act(async () => f.button(action).click());
    const firstDialog = f.dialog();
    assert.equal(f.writes.length, 0);
    const cancel = f.button("取消", firstDialog);
    assert.equal(f.window.document.activeElement, cancel);
    await f.act(async () => cancel.click());
    assert.equal(f.writes.length, 0);
    assert.equal(f.window.document.querySelector("dialog[open]"), null);
    assert.equal(f.window.document.activeElement, trigger);

    await f.act(async () => f.button(action).click());
    // Browsers dispatch this cancel event for Escape; happy-dom has no native key UI.
    await f.act(async () => {
      f.dialog().dispatchEvent(
        new f.window.Event("cancel", { cancelable: true }),
      );
    });
    assert.equal(f.writes.length, 0);
    assert.equal(f.window.document.querySelector("dialog[open]"), null);
    assert.equal(f.window.document.activeElement, trigger);

    await f.act(async () => f.button(action).click());
    const confirm = [...f.dialog().querySelectorAll("button")].find(
      (button) => button.textContent?.trim() !== "取消",
    );
    assert.ok(confirm);
    await f.act(async () => {
      confirm.click();
      confirm.click();
    });
    assert.equal(f.writes.length, 1);
    assert.deepEqual(
      f.writes[0],
      kind === "members"
        ? {
            path: "/api/admin/members/member",
            method: "PUT",
            body: { role: "MEMBER", active: false },
          }
        : {
            path: "/api/admin/keys/fixture-key",
            method: "DELETE",
            body: undefined,
          },
    );
    assert.match(
      f.container.textContent,
      kind === "members" ? /已停用/ : /已撤销/,
    );
  });

  test(`${kind}: a confirmed API failure remains visible without claiming success`, async (t) => {
    const f = await mount(t);
    f.rejectWrites();
    await f.act(async () => f.button(action).click());
    assert.equal(f.writes.length, 0);
    const confirm = [...f.dialog().querySelectorAll("button")].find(
      (button) => button.textContent?.trim() !== "取消",
    );
    assert.ok(confirm);
    await f.act(async () => confirm.click());
    assert.equal(f.writes.length, 1);
    assert.match(
      f.container.querySelector('[role="alert"]')?.textContent ?? "",
      /操作与当前状态冲突/,
    );
    assert.ok(f.button(action));
  });

  test(`${kind}: removing a consumer with a pending confirmation never sends its stale action`, async (t) => {
    const f = await mount(t);
    await f.act(async () => f.button(action).click());
    assert.ok(f.dialog());
    await f.act(async () => f.root.render(null));
    assert.equal(f.writes.length, 0);
    assert.equal(f.window.document.querySelector("dialog"), null);
  });
}

test("pending confirmation rejects another request and provider removal cancels the original action", async (t) => {
  const f = await fixture(t);
  const settled: { request: number; confirmed: boolean }[] = [];
  function Consumer() {
    const confirm = f.useConfirmation();
    return (
      <button
        onClick={() => {
          for (const request of [1, 2]) {
            void confirm({
              title: `Request ${request}`,
              description: "Fixture action",
              confirmLabel: "继续",
            }).then((confirmed) => settled.push({ request, confirmed }));
          }
        }}
      >
        请求确认
      </button>
    );
  }
  await f.act(async () =>
    f.root.render(
      <f.ConfirmationProvider>
        <Consumer />
      </f.ConfirmationProvider>,
    ),
  );
  await f.act(async () => f.button("请求确认").click());
  assert.match(f.dialog().textContent, /Request 1/);
  assert.deepEqual(settled, [{ request: 2, confirmed: false }]);
  await f.act(async () => f.root.render(null));
  assert.deepEqual(settled, [
    { request: 2, confirmed: false },
    { request: 1, confirmed: false },
  ]);
  assert.equal(f.writes.length, 0);
});

test("Admin logout completing while member confirmation is open cancels the unmounted member action", async (t) => {
  const f = await fixture(t);
  const { Admin } = await import("../components/admin");
  const managementApi = globalThis.fetch;
  let finishLogout!: (response: Response) => void;
  const logoutResponse = new Promise<Response>((resolve) => {
    finishLogout = resolve;
  });
  let logoutRequests = 0;
  globalThis.fetch = async (input, options) => {
    const path = new URL(String(input), "http://localhost").pathname;
    if (path === "/api/auth/me")
      return Response.json({
        accountId: "owner",
        workspaceId: "workspace",
        role: "OWNER",
        capabilities: ["READ_PRIVATE", "WRITE_PRIVATE", "MANAGE_KEYS"],
      });
    if (path === "/api/admin/repository/tree")
      return Response.json({ commit: "fixture", entries: [], diagnostics: [] });
    if (path === "/api/auth/logout") {
      assert.equal(options?.method, "POST");
      logoutRequests++;
      return logoutResponse;
    }
    return managementApi(input, options);
  };
  await f.act(async () => f.root.render(<Admin />));
  await f.act(async () => f.button("成员与邀请").click());
  await f.act(async () => f.button("退出登录").click());
  assert.equal(logoutRequests, 1);
  assert.ok(f.button("停用"));
  await f.act(async () => f.button("停用").click());
  assert.ok(f.dialog());
  assert.equal(f.writes.length, 0);

  // Admin's provider survives the switch to Login; only Members unmounts.
  await f.act(async () => finishLogout(new Response(null, { status: 204 })));
  assert.ok(f.container.querySelector('form input[name="login"]'));
  assert.equal(f.window.document.querySelector("dialog"), null);
  assert.equal(f.container.querySelector(".management-panel"), null);
  assert.equal(f.writes.length, 0);
  assert.equal(logoutRequests, 1);
});
