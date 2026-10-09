import assert from "node:assert/strict";
import test from "node:test";
import { Window } from "happy-dom";

test("account consent is explicit, client names are text, and a failed update does not claim success", async (t) => {
  const window = new Window({ url: "http://localhost/admin" });
  const globals = {
    window,
    document: window.document,
    navigator: window.navigator,
    HTMLElement: window.HTMLElement,
    HTMLInputElement: window.HTMLInputElement,
    Event: window.Event,
    IS_REACT_ACT_ENVIRONMENT: true,
  };
  const old = new Map(
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
  const originalFetch = globalThis.fetch;
  const writes: unknown[] = [];
  globalThis.fetch = async (input, options) => {
    if (String(input) === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    if (options?.method === "PUT") {
      writes.push(JSON.parse(String(options.body)));
      throw new Error("The reply was lost.");
    }
    return Response.json({
      items: [
        {
          keyId: "11111111-1111-1111-1111-111111111111",
          workspaceName: "A space",
          clientName: "<img src=x onerror=alert(1)>",
          permissions: [],
        },
      ],
      nextOffset: null,
    });
  };
  const { act } = await import("react");
  const { createRoot } = await import("react-dom/client");
  const { MachineGrants } = await import("../components/machine-grants");
  const container = window.document.createElement("div");
  window.document.body.append(container);
  const root = createRoot(container as unknown as HTMLDivElement);
  t.after(async () => {
    await act(async () => root.unmount());
    globalThis.fetch = originalFetch;
    await window.happyDOM.close();
    for (const [key, value] of old) {
      if (value) Object.defineProperty(globalThis, key, value);
      else Reflect.deleteProperty(globalThis, key);
    }
  });
  await act(async () => root.render(<MachineGrants />));
  assert.equal(writes.length, 0);
  await act(async () => container.querySelector("button")!.click());
  assert.equal(writes.length, 0);
  assert.equal(container.querySelector("img"), null);
  assert.ok(container.textContent.includes("<img src=x onerror=alert(1)>"));
  const checkbox = container.querySelector('input[type="checkbox"]')!;
  await act(async () => (checkbox as unknown as { click(): void }).click());
  assert.deepEqual(writes, [{ permissions: ["POCKET"] }]);
  assert.ok(container.querySelector('[role="alert"]'));
  assert.equal(
    (container.querySelector("input") as unknown as { checked: boolean })
      .checked,
    false,
  );
});
