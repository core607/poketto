import assert from "node:assert/strict";
import test, { type TestContext } from "node:test";
import { Window, type HTMLInputElement } from "happy-dom";
import {
  githubRoot,
  type GitHubRequest,
  type GitHubResult,
  type GitHubStatus,
} from "../lib/github-spaces";

const connected: GitHubStatus = {
  available: true,
  eligibleToCreate: true,
  state: "CONNECTED",
  githubUserId: 42,
  login: "octocat",
  version: 1,
};
const request: GitHubRequest = {
  requestId: "11111111-1111-4111-8111-111111111111",
  displayName: "My notes",
  slug: "my-notes",
  githubOwnerId: 42,
  repositoryName: "notes",
};
function receipt(stage: GitHubResult["stage"]): GitHubResult {
  return {
    requestId: request.requestId,
    workspaceId: "new-space",
    stage,
    repositoryId: 91,
    repository: "https://github.com/octocat/notes",
    workspaceCreated: stage === "READY" || stage === "INITIALIZING",
    initializationCommit: stage === "READY" ? "a".repeat(40) : null,
    failureCode: null,
    retryAfterSeconds: 0,
  };
}
async function fixture(t: TestContext) {
  const window = new Window({ url: "https://site.example/admin?tab=account" });
  const globals = {
    window,
    document: window.document,
    navigator: window.navigator,
    HTMLElement: window.HTMLElement,
    HTMLInputElement: window.HTMLInputElement,
    FormData: window.FormData,
    sessionStorage: window.sessionStorage,
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
  const { GitHubSpace } = await import("../components/github-space");
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
  const button = (label: string) => {
    const value = [...container.querySelectorAll("button")].find(
      (item) => item.textContent === label,
    );
    assert.ok(value, "Missing button: " + label);
    return value;
  };
  let mayLeave = true;
  const entered: string[] = [];
  const render = () =>
    act(async () =>
      root.render(
        <GitHubSpace
          accountId="fixture-account"
          onBeforeLeave={async () => mayLeave}
          onCreated={async (id) => {
            entered.push(id);
          }}
        />,
      ),
    );
  function initial(path: string, status = connected, items: unknown[] = []) {
    if (path === githubRoot) return Response.json(status);
    if (path === githubRoot + "/creations")
      return Response.json({ items, nextOffset: null });
    if (path === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture-csrf" });
  }
  return {
    window,
    container,
    act,
    render,
    button,
    entered,
    initial,
    setMayLeave: (value: boolean) => {
      mayLeave = value;
    },
  };
}

test("explicit creation retains its request identity through uncertainty and sends no visibility override", async (t) => {
  const f = await fixture(t);
  const sent: GitHubRequest[] = [];
  globalThis.fetch = async (input, options) => {
    const path = String(input);
    if (options?.method === "POST") {
      assert.equal(path, githubRoot + "/creations");
      assert.equal(new Headers(options.headers).get("X-CSRF"), "fixture-csrf");
      sent.push(JSON.parse(String(options.body)));
      return Response.json({
        ...receipt("UNCERTAIN"),
        requestId: sent.at(-1)!.requestId,
        repositoryId: null,
        repository: null,
      });
    }
    const response = f.initial(path);
    assert.ok(response, path);
    return response;
  };
  await f.render();
  assert.equal(sent.length, 0);
  for (const [name, value] of Object.entries({
    displayName: "My notes",
    slug: "my-notes",
    repositoryName: "notes",
  })) {
    f.container.querySelector<HTMLInputElement>(
      `input[name="${name}"]`,
    )!.value = value;
  }
  await f.act(async () =>
    f.container
      .querySelector("form")!
      .dispatchEvent(
        new f.window.Event("submit", { bubbles: true, cancelable: true }),
      ),
  );
  assert.equal(sent.length, 1);
  assert.match(f.container.textContent, /确认仓库是否已创建/);
  assert.equal(sent[0].githubOwnerId, 42);
  assert.deepEqual(Object.keys(sent[0]).sort(), [
    "displayName",
    "githubOwnerId",
    "repositoryName",
    "requestId",
    "slug",
  ]);
  await f.act(async () => f.button("继续这次申请").click());
  assert.equal(sent.length, 2);
  assert.deepEqual(sent[0], sent[1]);
  assert.deepEqual(
    JSON.parse(
      f.window.sessionStorage.getItem(
        "poketto.github-creation.fixture-account",
      )!,
    ),
    sent[0],
  );
  assert.equal(f.entered.length, 0);
});

test("server history restores a repository and installation selection resumes without another creation", async (t) => {
  const f = await fixture(t);
  let resumes = 0;
  globalThis.fetch = async (input, options) => {
    const path = String(input);
    if (path.endsWith("/resume")) {
      resumes++;
      return Response.json(
        resumes === 1
          ? {
              ...receipt("AWAITING_INSTALLATION"),
              failureCode: "INSTALLATION_REQUIRED",
            }
          : receipt("READY"),
      );
    }
    if (path.endsWith("/installation"))
      return Response.json({
        url: "https://github.com/settings/installations/7",
      });
    assert.notEqual(
      options?.method,
      "POST",
      "No repository-creation POST during restoration",
    );
    const response = f.initial(path, connected, [
      { request, result: receipt("AWAITING_INSTALLATION") },
    ]);
    assert.ok(response, path);
    return response;
  };
  await f.render();
  const history = [...f.container.querySelectorAll("button")].find((value) =>
    value.textContent?.startsWith("My notes · notes"),
  )!;
  await f.act(async () => history.click());
  await f.act(async () => f.button("继续准备空间").click());
  assert.match(f.container.textContent, /选中这个仓库/);
  assert.equal(
    [...f.container.querySelectorAll("button")].some(
      (value) => value.textContent === "进入空间",
    ),
    false,
  );
  await f.act(async () => f.button("管理 GitHub 仓库授权").click());
  const link = f.container.querySelector(
    'a[href="https://github.com/settings/installations/7"]',
  );
  assert.ok(link);
  assert.equal(link.getAttribute("rel"), "noopener noreferrer");
  await f.act(async () => f.button("继续准备空间").click());
  assert.match(f.container.textContent, /空间已准备好/);
  f.setMayLeave(false);
  await f.act(async () => f.button("进入空间").click());
  assert.equal(f.entered.length, 0);
  f.setMayLeave(true);
  await f.act(async () => f.button("进入空间").click());
  assert.deepEqual(f.entered, ["new-space"]);
});

test("an existing downgraded owner can manage repository access without an active creation request", async (t) => {
  const f = await fixture(t);
  const posts: string[] = [];
  globalThis.fetch = async (input, options) => {
    const path = String(input);
    if (options?.method === "POST") {
      posts.push(path);
      assert.equal(path, githubRoot + "/installation");
      assert.equal(new Headers(options.headers).get("X-CSRF"), "fixture-csrf");
      return Response.json({
        url: "https://github.com/settings/installations/7",
      });
    }
    const response = f.initial(
      path,
      { ...connected, eligibleToCreate: false },
      [{ request, result: receipt("READY") }],
    );
    assert.ok(response, path);
    return response;
  };
  await f.render();
  assert.equal(f.container.querySelector("form"), null);
  assert.equal(f.window.sessionStorage.length, 0);
  await f.act(async () => f.button("管理 GitHub 仓库授权").click());
  const link = f.container.querySelector(
    'a[href="https://github.com/settings/installations/7"]',
  );
  assert.ok(link);
  assert.equal(link.textContent, "打开 GitHub 仓库授权设置");
  assert.equal(link.getAttribute("target"), "_blank");
  assert.equal(link.getAttribute("rel"), "noopener noreferrer");
  assert.deepEqual(posts, [githubRoot + "/installation"]);
  assert.equal(f.entered.length, 0);
});

test("a downgraded existing owner may finish initialization but sees no new-space form", async (t) => {
  const f = await fixture(t);
  f.window.sessionStorage.setItem(
    "poketto.github-creation.fixture-account",
    JSON.stringify(request),
  );
  let resumes = 0;
  globalThis.fetch = async (input, options) => {
    const path = String(input);
    if (path.endsWith("/resume")) {
      resumes++;
      return Response.json(receipt("READY"));
    }
    assert.notEqual(options?.method, "POST");
    const response = f.initial(
      path,
      { ...connected, eligibleToCreate: false },
      [{ request, result: receipt("INITIALIZING") }],
    );
    assert.ok(response, path);
    return response;
  };
  await f.render();
  assert.equal(f.container.querySelector("form"), null);
  await f.act(async () => f.button("继续准备空间").click());
  assert.equal(resumes, 1);
  assert.match(f.container.textContent, /空间已准备好/);
});

test("authorizing GitHub respects dirty-editor navigation and never creates a repository", async (t) => {
  const f = await fixture(t);
  let starts = 0;
  globalThis.fetch = async (input, options) => {
    const path = String(input);
    if (path.endsWith("/start")) {
      starts++;
      return Response.json({
        url: "https://github.com/login/oauth/authorize?state=fixture",
      });
    }
    assert.notEqual(options?.method, "POST");
    const response = f.initial(path, {
      ...connected,
      state: "NOT_CONNECTED",
      githubUserId: null,
      login: null,
      version: 0,
    });
    assert.ok(response, path);
    return response;
  };
  await f.render();
  f.setMayLeave(false);
  await f.act(async () => f.button("授权 GitHub").click());
  assert.equal(starts, 0);
  f.setMayLeave(true);
  await f.act(async () => f.button("授权 GitHub").click());
  assert.equal(starts, 1);
  assert.equal(f.window.location.hostname, "github.com");
});

test("creating another space after entering a ready space submits a new request", async (t) => {
  const f = await fixture(t);
  f.window.sessionStorage.setItem(
    "poketto.github-creation.fixture-account",
    JSON.stringify(request),
  );
  const sent: GitHubRequest[] = [];
  globalThis.fetch = async (input, options) => {
    const path = String(input);
    if (options?.method === "POST") {
      assert.equal(path, githubRoot + "/creations");
      const next: GitHubRequest = JSON.parse(String(options.body));
      sent.push(next);
      return Response.json({
        ...receipt("UNCERTAIN"),
        requestId: next.requestId,
        repositoryId: null,
        repository: null,
      });
    }
    const response = f.initial(path, connected, [
      { request, result: receipt("READY") },
    ]);
    assert.ok(response, path);
    return response;
  };
  await f.render();
  await f.act(async () => f.button("进入空间").click());
  assert.deepEqual(f.entered, ["new-space"]);
  for (const [name, value] of Object.entries({
    displayName: "Second notes",
    slug: "second-notes",
    repositoryName: "second-notes",
  })) {
    f.container.querySelector<HTMLInputElement>(
      `input[name="${name}"]`,
    )!.value = value;
  }
  await f.act(async () =>
    f.container
      .querySelector("form")!
      .dispatchEvent(
        new f.window.Event("submit", { bubbles: true, cancelable: true }),
      ),
  );
  assert.equal(sent.length, 1);
  assert.notEqual(sent[0].requestId, request.requestId);
  assert.equal(sent[0].repositoryName, "second-notes");
  assert.match(f.container.textContent, /确认仓库是否已创建/);
});
