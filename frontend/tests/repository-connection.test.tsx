import assert from "node:assert/strict";
import test, { type TestContext } from "node:test";
import { Window, type HTMLInputElement } from "happy-dom";

async function fixture(t: TestContext) {
  const window = new Window({ url: "https://site.example/admin" });
  const globals = {
    window,
    document: window.document,
    navigator: window.navigator,
    HTMLElement: window.HTMLElement,
    HTMLInputElement: window.HTMLInputElement,
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
  const { RepositoryConnection } =
    await import("../components/repository-connection");
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
  return { window, act, root, container, RepositoryConnection };
}

const managed = {
  managed: true,
  rotationAvailable: true,
  binding: {
    repository: "https://cnb.cool/example/notes",
    updatedAt: "2026-09-11T00:00:00Z",
  },
};

test("credential submission stays workspace-scoped, clears secrets and shows success only after acknowledgement", async (t) => {
  const f = await fixture(t);
  let resolveWrite!: (response: Response) => void;
  let captured: unknown;
  const paths: string[] = [];
  globalThis.fetch = async (input, options) => {
    const path = String(input);
    paths.push(path);
    if (path === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    if (options?.method === "PUT") {
      assert.equal(
        path,
        "/api/auth/workspaces/selected-space/repository-credentials",
      );
      captured = JSON.parse(String(options.body));
      return new Promise<Response>((resolve) => {
        resolveWrite = resolve;
      });
    }
    assert.equal(
      path,
      "/api/auth/workspaces/selected-space/repository-connection",
    );
    return Response.json(managed);
  };
  await f.act(async () =>
    f.root.render(<f.RepositoryConnection workspaceId="selected-space" />),
  );
  const username = f.container.querySelector<HTMLInputElement>(
    'input[name="username"]',
  )!;
  const token = f.container.querySelector<HTMLInputElement>(
    'input[name="token"]',
  )!;
  assert.equal(token.type, "password");
  username.value = "fixture-user";
  token.value = "fixture-provider-token";
  await f.act(async () =>
    f.container
      .querySelector("form")!
      .dispatchEvent(
        new f.window.Event("submit", { bubbles: true, cancelable: true }),
      ),
  );
  assert.deepEqual(captured, {
    username: "fixture-user",
    token: "fixture-provider-token",
  });
  assert.equal(username.value, "");
  assert.equal(token.value, "");
  assert.equal(f.window.sessionStorage.length, 0);
  assert.equal(f.window.localStorage.length, 0);
  assert.doesNotMatch(f.container.textContent, /凭据已更新/);
  assert.equal(f.container.querySelector("fieldset")!.disabled, true);
  await f.act(async () => resolveWrite(new Response(null, { status: 204 })));
  assert.match(f.container.textContent, /仓库凭据已更新/);
  assert.equal(
    paths.filter((path) => path.endsWith("/repository-connection")).length,
    2,
  );
});

test("rejected credentials keep the existing binding visible and do not echo provider response secrets", async (t) => {
  const f = await fixture(t);
  globalThis.fetch = async (input, options) => {
    if (String(input) === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    return options?.method === "PUT"
      ? Response.json(
          {
            code: "PERMISSION_DENIED",
            detail: "provider-secret-must-not-render",
          },
          { status: 503 },
        )
      : Response.json(managed);
  };
  await f.act(async () =>
    f.root.render(<f.RepositoryConnection workspaceId="selected" />),
  );
  await f.act(async () =>
    f.container
      .querySelector("form")!
      .dispatchEvent(
        new f.window.Event("submit", { bubbles: true, cancelable: true }),
      ),
  );
  assert.match(f.container.textContent, /cnb.cool\/example\/notes/);
  assert.match(f.container.textContent, /新令牌未通过权限验证/);
  assert.doesNotMatch(f.container.textContent, /provider-secret|凭据已更新/);
  assert.equal(f.container.querySelector("fieldset")!.disabled, false);
});

test("operator-managed repositories and missing encryption configuration expose no rotation form", async (t) => {
  const f = await fixture(t);
  globalThis.fetch = async () =>
    Response.json({ managed: false, rotationAvailable: true, binding: null });
  await f.act(async () =>
    f.root.render(
      <f.RepositoryConnection key="operator" workspaceId="operator" />,
    ),
  );
  assert.match(f.container.textContent, /部署配置管理/);
  assert.equal(f.container.querySelector("form"), null);
  globalThis.fetch = async () =>
    Response.json({ ...managed, rotationAvailable: false });
  await f.act(async () =>
    f.root.render(
      <f.RepositoryConnection key="managed" workspaceId="managed" />,
    ),
  );
  assert.match(f.container.textContent, /加密密钥/);
  assert.equal(f.container.querySelector("form"), null);
});
