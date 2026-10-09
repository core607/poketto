import assert from "node:assert/strict";
import test from "node:test";
import { Window } from "happy-dom";
import { NextRequest } from "next/server";
import { GET } from "../app/games/frame/route";
import { proxy } from "../proxy";

test("game documents deny API connections and only their static modules receive opaque-origin CORS", async () => {
  const frame = GET(new NextRequest("https://pocket.example/games/frame"));
  const policy = frame.headers.get("Content-Security-Policy")!;
  assert.match(policy, /connect-src 'none'/);
  assert.match(policy, /worker-src blob:/);
  const scripts = policy
    .split("; ")
    .find((value) => value.startsWith("script-src"))!;
  assert.equal(scripts.includes("'self'"), false);
  assert.equal(scripts.includes("unsafe-eval"), false);
  assert.match(scripts, /https:\/\/pocket\.example\/games\/runtime\.mjs/);
  assert.equal(frame.headers.get("Cache-Control"), "no-store");
  const source = await frame.text();
  assert.match(source, /crossorigin="anonymous"/);
  assert.equal(
    proxy(new NextRequest("https://pocket.example/admin")).headers.get(
      "Access-Control-Allow-Origin",
    ),
    null,
  );
  assert.equal(
    proxy(
      new NextRequest("https://pocket.example/games/runtime.mjs"),
    ).headers.get("Access-Control-Allow-Origin"),
    "*",
  );
  assert.equal(
    proxy(new NextRequest("https://pocket.example/games/frame")).headers.get(
      "Content-Security-Policy",
    ),
    null,
  );
});

test("anonymous game entrance requests only a validated package and gives its fixed iframe no account origin", async (t) => {
  const window = new Window({
    url: "http://localhost/s/street/read/game",
    settings: { disableIframePageLoading: true },
  });
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
  const requests: string[] = [];
  globalThis.fetch = async (input, options) => {
    assert.equal(options?.method ?? "GET", "GET");
    requests.push(String(input));
    return Response.json({
      articleId: "123",
      version: "a".repeat(64),
      title: "<img src=x> Puzzle",
      help: "Inspect and open",
      bundle: {
        protocol: 1,
        source: "rules",
        presentation: null,
        resources: {},
      },
    });
  };
  const { act } = await import("react");
  const { createRoot } = await import("react-dom/client");
  const { GamePlayer } = await import("../components/game-player");
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
  await act(async () =>
    root.render(<GamePlayer space="street" articleId="123" />),
  );
  assert.equal(container.querySelector("iframe"), null);
  assert.equal(container.querySelector("img"), null);
  assert.match(container.textContent, /<img src=x> Puzzle/);
  await act(async () => container.querySelector("button")!.click());
  const frame = container.querySelector("iframe")!;
  assert.equal(frame.getAttribute("sandbox"), "allow-scripts");
  assert.equal(frame.getAttribute("src"), "/games/frame");
  assert.deepEqual(requests, ["/api/public/games/spaces/street/articles/123"]);
});
