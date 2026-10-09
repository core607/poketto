import assert from "node:assert/strict";
import test from "node:test";
import { Window } from "happy-dom";
import type { GamePackage, GameUpload } from "../lib/games";

test("cloud handoff is explicit, pins revisions, and retries uncertain creation with the original state", async (t) => {
  const window = new Window({ url: "http://localhost/s/street/read/game" });
  const globals = {
    window,
    document: window.document,
    navigator: window.navigator,
    HTMLElement: window.HTMLElement,
    Event: window.Event,
    IS_REACT_ACT_ENVIRONMENT: true,
  };
  const previous = new Map(
    Object.keys(globals).map((key) => [
      key,
      Object.getOwnPropertyDescriptor(globalThis, key),
    ]),
  );
  for (const [key, value] of Object.entries(globals))
    Object.defineProperty(globalThis, key, {
      configurable: true,
      writable: true,
      value,
    });
  const oldFetch = globalThis.fetch;
  const game: GamePackage = {
    workspaceId: "workspace",
    articleId: "article",
    version: "sha256:" + "a".repeat(64),
    title: "Puzzle",
    help: "Help",
    bundle: { protocol: 1, source: "rules", presentation: null, resources: {} },
  };
  const writes: GameUpload[] = [];
  let state = { turn: 1 },
    next = "1",
    conflict = false,
    persisted = false;
  const saved = () => ({
    saveId: "save",
    workspaceId: "workspace",
    articleId: "article",
    revision: "2",
    title: "Puzzle",
    packageVersion: game.version,
    result: { state: { turn: 8 } },
  });
  globalThis.fetch = async (input, options) => {
    const url = String(input);
    if (url === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    if (options?.method === "POST") {
      writes.push(JSON.parse(String(options.body)));
      if (writes.length === 1) {
        persisted = true;
        next = "2";
        throw new Error("Reply lost");
      }
      if (conflict)
        return Response.json({ code: "SAVE_CONFLICT" }, { status: 409 });
      return Response.json(saved());
    }
    if (url.endsWith("/save")) return Response.json(saved());
    assert.equal(url, "/api/games/saves");
    return Response.json({
      accountId: "account",
      nextCreationRequest: next,
      items: persisted
        ? [
            { ...saved(), status: "READY" },
            {
              ...saved(),
              saveId: "other",
              workspaceId: "another",
              status: "READY",
            },
          ]
        : [],
    });
  };
  const { act } = await import("react");
  const { createRoot } = await import("react-dom/client");
  const { GameSaves } = await import("../components/game-saves");
  const container = window.document.createElement("div");
  window.document.body.append(container);
  const root = createRoot(container as unknown as HTMLDivElement);
  t.after(async () => {
    await act(async () => root.unmount());
    globalThis.fetch = oldFetch;
    await window.happyDOM.close();
    for (const [key, value] of previous) {
      if (value) Object.defineProperty(globalThis, key, value);
      else Reflect.deleteProperty(globalThis, key);
    }
  });
  const buttons = (label: string) =>
    [...container.querySelectorAll("button")].filter(
      (button) => button.textContent === label,
    );
  const click = async (label: string) => {
    const button = buttons(label)[0];
    assert.ok(button, label);
    await act(async () => button.click());
  };
  let resumed: unknown;
  await act(async () =>
    root.render(
      <GameSaves
        space="street"
        game={game}
        hasState
        snapshot={() => state}
        resume={(value) => {
          resumed = value;
        }}
      />,
    ),
  );
  assert.equal(writes.length, 0);
  await click("管理账号存档");
  await click("保存当前进度");
  assert.match(container.textContent, /结果尚未确认/);
  assert.equal(buttons("保存当前进度")[0].disabled, true);
  state = { turn: 4 };
  await click("刷新云端存档");
  await click("重试同一份进度");
  assert.deepEqual(writes[1], writes[0]);
  assert.equal(writes[1].creationRequest, "1");
  assert.deepEqual(writes[1].state, { turn: 1 });
  assert.equal(
    resumed,
    undefined,
    "saving must not discard newer local progress",
  );
  assert.match(container.textContent, /云端随后又有了新进度/);
  conflict = true;
  await click("保存当前进度");
  assert.equal(writes[2].expectedRevision, "1");
  assert.match(container.textContent, /云端进度已经变化/);
  assert.equal(
    buttons("载入并替换当前进度").length,
    1,
    "same article ID in a different workspace is not the same game",
  );
  await click("载入并替换当前进度");
  assert.deepEqual(resumed, { turn: 8 });
  assert.equal(
    writes.length,
    3,
    "loading and rendering never call the save endpoint",
  );
  let finishLoad: (response: Response) => void = () => {
    throw new Error("Load did not start");
  };
  globalThis.fetch = async () =>
    new Promise<Response>((resolve) => {
      finishLoad = resolve;
    });
  await click("载入并替换当前进度");
  await act(async () => root.render(<p>Another article</p>));
  resumed = undefined;
  await act(async () => {
    finishLoad(Response.json(saved()));
  });
  assert.equal(
    resumed,
    undefined,
    "a late load must not pass this game's private save to another article",
  );
});
