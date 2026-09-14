import assert from "node:assert/strict";
import test from "node:test";
import { Window } from "happy-dom";
import { workspaceId } from "./workspace-fixture";

type RequestHandler = (url: URL, options?: RequestInit) => Promise<Response>;
type Control = {
  value: string;
  dispatchEvent: (event: unknown) => boolean;
};

const account = {
  account: {
    accountId: "owner",
    loginName: "owner",
    siteAdministrator: false,
  },
  mayIssueRegistrationInvitations: false,
};

function json(value: unknown) {
  return Response.json(value);
}

function preserveForwardEntries(window: Window) {
  // happy-dom drops forward entries when replaceState follows traversal;
  // browsers retain them, so keep the fixture aligned with the History API.
  type Entry = { state: unknown; url: string };
  type HistoryMethods = {
    pushState: (
      state: unknown,
      title: string,
      url?: string | URL | null,
    ) => void;
    replaceState: (
      state: unknown,
      title: string,
      url?: string | URL | null,
    ) => void;
  };
  const history = window.history as unknown as HistoryMethods;
  const nativePush = history.pushState.bind(history);
  const nativeReplace = history.replaceState.bind(history);
  const nativeGo = window.history.go.bind(window.history);
  const entries: Entry[] = [
    { state: window.history.state, url: window.location.href },
  ];
  let index = 0;
  let restoring = false;
  const sync = () => {
    const current = window.location.href;
    const next = entries.findIndex((entry) => entry.url === current);
    if (next >= 0) index = next;
  };
  const onPopState = (event: unknown) => {
    sync();
    if (restoring) {
      restoring = false;
      (
        event as { stopImmediatePropagation: () => void }
      ).stopImmediatePropagation();
    }
  };
  window.addEventListener("popstate", onPopState);
  history.pushState = (state, title, url) => {
    const nextUrl = new URL(
      String(url ?? window.location.href),
      window.location.href,
    ).href;
    entries.splice(index + 1);
    entries.push({ state, url: nextUrl });
    index += 1;
    nativePush(state, title, url);
  };
  history.replaceState = (state, title, url) => {
    const nextUrl = new URL(
      String(url ?? window.location.href),
      window.location.href,
    ).href;
    const forward = entries.slice(index + 1);
    entries[index] = { state, url: nextUrl };
    nativeReplace(state, title, url);
    for (const entry of forward) nativePush(entry.state, "", entry.url);
    if (forward.length) {
      restoring = true;
      nativeGo(-forward.length);
    }
  };
  return () => {
    window.removeEventListener("popstate", onPopState);
    history.pushState = nativePush;
    history.replaceState = nativeReplace;
  };
}

async function mountDashboard(url: string, handler: RequestHandler) {
  const window = new Window({ url });
  const globals = {
    window,
    document: window.document,
    navigator: window.navigator,
    HTMLElement: window.HTMLElement,
    HTMLDialogElement: window.HTMLDialogElement,
    HTMLInputElement: window.HTMLInputElement,
    HTMLTextAreaElement: window.HTMLTextAreaElement,
    HTMLSelectElement: window.HTMLSelectElement,
    FormData: window.FormData,
    Event: window.Event,
    IS_REACT_ACT_ENVIRONMENT: true,
    requestAnimationFrame: window.requestAnimationFrame.bind(window),
    cancelAnimationFrame: window.cancelAnimationFrame.bind(window),
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
  const previousFetch = globalThis.fetch;
  const restoreHistory = preserveForwardEntries(window);
  globalThis.fetch = (input, options) =>
    handler(new URL(String(input), "http://localhost"), options);
  const { act } = await import("react");
  const { createRoot } = await import("react-dom/client");
  const { WorkspaceDashboard } =
    await import("../components/workspace-dashboard");
  const { ConfirmationProvider } = await import("../components/confirmation");
  const container = window.document.createElement("div");
  window.document.body.append(container);
  const root = createRoot(container as unknown as HTMLDivElement);
  let mounted = true;
  await act(async () =>
    root.render(
      <ConfirmationProvider>
        <WorkspaceDashboard account={account} onLogout={async () => {}} />
      </ConfirmationProvider>,
    ),
  );
  return {
    window,
    container,
    act,
    async cleanup() {
      if (mounted) {
        await act(async () => root.unmount());
        mounted = false;
      }
      globalThis.fetch = previousFetch;
      restoreHistory();
      await window.happyDOM.close();
      for (const [name, descriptor] of previous) {
        if (descriptor) Object.defineProperty(globalThis, name, descriptor);
        else Reflect.deleteProperty(globalThis, name);
      }
    },
  };
}

async function flush(
  act: (callback: () => unknown) => Promise<unknown>,
  rounds = 6,
) {
  for (let index = 0; index < rounds; index++)
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
}

async function contentApi(url: URL, options?: RequestInit) {
  if (url.pathname === "/api/auth/workspaces")
    return json({
      items: [
        {
          workspaceId,
          displayName: "Fixture space",
          role: "OWNER",
          capabilities: ["READ_PRIVATE", "WRITE_PRIVATE", "PUBLISH"],
        },
      ],
      total: 1,
      offset: 0,
      limit: 30,
    });
  if (url.pathname === `/api/auth/workspaces/${workspaceId}/me`)
    return json({
      accountId: "owner",
      workspaceId,
      displayName: "Fixture space",
      role: "OWNER",
      capabilities: ["READ_PRIVATE", "WRITE_PRIVATE", "PUBLISH"],
    });
  if (url.pathname === "/api/auth/csrf")
    return json({ headerName: "X-CSRF", token: "fixture" });
  if (url.pathname.endsWith("/repository/tree"))
    return json({ commit: "before", entries: [], diagnostics: [] });
  if (url.pathname.endsWith("/repository/directory")) {
    const path = url.searchParams.get("path") ?? "";
    const entries =
      path === ""
        ? [{ path: "private", kind: "DIRECTORY" as const }]
        : path === "private"
          ? [
              { path: "private/a.md", kind: "FILE" as const },
              { path: "private/b", kind: "DIRECTORY" as const },
              { path: "private/c", kind: "DIRECTORY" as const },
            ]
          : [];
    return json({
      commit: "before",
      path,
      expectedAbsence: false,
      entries,
      nextOffset: null,
    });
  }
  if (url.pathname.endsWith("/repository/file")) {
    const path = url.searchParams.get("path") ?? "private/a.md";
    return json({
      path,
      source: "# Original",
      revision: "revision",
      commit: "before",
      expectedAbsence: false,
      publicScope: false,
      diagnostics: [],
    });
  }
  if (url.pathname.endsWith("/repository/preview"))
    return json({ body: "# preview", images: {}, galleryStatus: "COMPLETE" });
  throw new Error(`Unexpected API call: ${options?.method ?? "GET"} ${url}`);
}

function summary(
  container: { querySelectorAll: typeof document.querySelectorAll },
  label: string,
) {
  const item = [...container.querySelectorAll("summary")].find(
    (candidate) => candidate.textContent?.trim() === label,
  );
  assert.ok(item, label);
  return item;
}

function button(
  container: { querySelectorAll: typeof document.querySelectorAll },
  label: string,
) {
  const item = [...container.querySelectorAll("button")].find(
    (candidate) => candidate.textContent?.trim() === label,
  );
  assert.ok(item, label);
  return item;
}

async function setDraft(
  act: (callback: () => unknown) => Promise<unknown>,
  window: Window,
  container: { querySelector: typeof document.querySelector },
  value: string,
) {
  const textarea = container.querySelector("textarea") as Control | null;
  assert.ok(textarea);
  Object.getOwnPropertyDescriptor(
    window.HTMLTextAreaElement.prototype,
    "value",
  )!.set!.call(textarea, value);
  await act(async () =>
    textarea.dispatchEvent(new window.Event("input", { bubbles: true })),
  );
}

function folder(window: Window) {
  return new URL(window.location.href).searchParams.get("folder");
}

test("dirty Back cancel stays on B, confirmation reaches A, and canceled Forward keeps B", async (t) => {
  const dashboard = await mountDashboard(
    "http://localhost/admin?tab=content&path=private%2Fa.md&folder=private%2Fa",
    contentApi,
  );
  t.after(() => dashboard.cleanup());
  await flush(dashboard.act, 10);
  await dashboard.act(async () => summary(dashboard.container, "b").click());
  await flush(dashboard.act);
  assert.equal(folder(dashboard.window), "private/b");
  await setDraft(
    dashboard.act,
    dashboard.window,
    dashboard.container,
    "# B draft",
  );
  await flush(dashboard.act);
  const historyLength = dashboard.window.history.length;

  await dashboard.act(async () => dashboard.window.history.back());
  await flush(dashboard.act, 10);
  assert.ok(dashboard.container.querySelector("dialog[open]"));
  await dashboard.act(async () =>
    button(dashboard.container.querySelector("dialog[open]")!, "取消").click(),
  );
  await flush(dashboard.act, 10);
  assert.equal(folder(dashboard.window), "private/b");
  assert.equal(dashboard.window.history.length, historyLength);

  await dashboard.act(async () => dashboard.window.history.back());
  await flush(dashboard.act, 10);
  const backDialog = dashboard.container.querySelector("dialog[open]");
  assert.ok(backDialog);
  await dashboard.act(async () => button(backDialog, "放弃并继续").click());
  await flush(dashboard.act, 12);
  assert.equal(folder(dashboard.window), "private/a");

  await setDraft(
    dashboard.act,
    dashboard.window,
    dashboard.container,
    "# A draft",
  );
  await flush(dashboard.act);
  await dashboard.act(async () => dashboard.window.history.forward());
  await flush(dashboard.act, 10);
  const forwardDialog = dashboard.container.querySelector("dialog[open]");
  assert.ok(forwardDialog);
  await dashboard.act(async () => button(forwardDialog, "取消").click());
  await flush(dashboard.act, 10);
  assert.equal(folder(dashboard.window), "private/a");
  assert.equal(dashboard.window.history.length, historyLength);

  await dashboard.act(async () => dashboard.window.history.forward());
  await flush(dashboard.act, 10);
  const preservedForwardDialog =
    dashboard.container.querySelector("dialog[open]");
  assert.ok(
    preservedForwardDialog,
    "the canceled forward must leave B available",
  );
  await dashboard.act(async () =>
    button(preservedForwardDialog, "放弃并继续").click(),
  );
  await flush(dashboard.act, 12);
  assert.equal(folder(dashboard.window), "private/b");
});

test("multiple Back events while confirmation is pending ask once and restore the accepted entry on cancel", async (t) => {
  const dashboard = await mountDashboard(
    "http://localhost/admin?tab=content&path=private%2Fa.md&folder=private%2Fa",
    contentApi,
  );
  t.after(() => dashboard.cleanup());
  await flush(dashboard.act, 10);
  await dashboard.act(async () => summary(dashboard.container, "b").click());
  await flush(dashboard.act);
  await dashboard.act(async () => summary(dashboard.container, "c").click());
  await flush(dashboard.act);
  assert.equal(folder(dashboard.window), "private/c");
  await setDraft(
    dashboard.act,
    dashboard.window,
    dashboard.container,
    "# C draft",
  );
  await flush(dashboard.act);

  await dashboard.act(async () => {
    dashboard.window.history.go(-1);
    dashboard.window.history.go(-1);
  });
  await flush(dashboard.act, 12);
  const dialogs = dashboard.container.querySelectorAll("dialog[open]");
  assert.equal(dialogs.length, 1);
  await dashboard.act(async () => button(dialogs[0], "取消").click());
  await flush(dashboard.act, 12);
  assert.equal(folder(dashboard.window), "private/c");
});
