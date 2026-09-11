import assert from "node:assert/strict";
import test from "node:test";
import { Window } from "happy-dom";

test("a signed-in owner must explicitly choose private permissions and consent never auto-submits", async (t) => {
  const window = new Window({
    url: "https://site.example/connect?request=fixture",
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
  const { Connect } = await import("../components/connect");
  const container = window.document.createElement("div");
  window.document.body.append(container);
  const root = createRoot(container as unknown as HTMLDivElement);
  const savedFetch = globalThis.fetch;
  const writes: unknown[] = [];
  globalThis.fetch = async (input, options) => {
    const path = String(input);
    if (path === "/api/auth/me") return Response.json({ role: "OWNER" });
    if (path === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    if (options?.method === "POST") {
      assert.equal(new Headers(options.headers).get("X-CSRF"), "fixture");
      writes.push(JSON.parse(String(options.body)));
      return new Response(null, { status: 400 });
    }
    return Response.json({
      clientName: "<img src=x onerror=alert(1)>",
      redirectUri: "https://client.example/callback",
      scopes: [
        "repository:execute",
        "content:read_private",
        "content:write_private",
        "content:publish",
        "offline_access",
      ],
    });
  };
  t.after(async () => {
    await act(async () => root.unmount());
    globalThis.fetch = savedFetch;
    await window.happyDOM.close();
    for (const [name, value] of previous) {
      if (value) Object.defineProperty(globalThis, name, value);
      else Reflect.deleteProperty(globalThis, name);
    }
  });
  await act(async () => root.render(<Connect />));
  const inputs = [...container.querySelectorAll("input")].filter(
    (input) => input.type === "checkbox",
  );
  assert.equal(inputs.length, 5);
  assert.deepEqual(
    inputs.map((input) => input.checked),
    [true, false, false, false, true],
  );
  assert.equal(container.querySelectorAll("img").length, 0);
  assert.equal(writes.length, 0);
  await act(async () => inputs[1].click());
  const approve = [...container.querySelectorAll("button")].find(
    (button) => button.textContent === "允许连接",
  );
  assert.ok(approve);
  await act(async () => approve.click());
  assert.deepEqual(writes, [
    {
      request: "fixture",
      scopes: ["repository:execute", "offline_access", "content:read_private"],
      allow: true,
    },
  ]);
  assert.ok(container.querySelector('[role="alert"]'));
  assert.equal(writes.length, 1);
});
