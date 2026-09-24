import assert from "node:assert/strict";
import test from "node:test";
import { Window } from "happy-dom";

async function mountBeacon(window: Window, space: string, route: string) {
  const { act } = await import("react");
  const { createRoot } = await import("react-dom/client");
  const { ViewBeacon } = await import("../components/view-beacon");
  const container = window.document.createElement("div");
  window.document.body.append(container);
  const root = createRoot(container as unknown as HTMLDivElement);
  await act(async () =>
    root.render(<ViewBeacon space={space} route={route} />),
  );
  return () => act(async () => root.unmount());
}

test("a reader is reported once per day after five visible seconds", async (t) => {
  const window = new Window({ url: "http://localhost/s/home/read/rain" });
  const sent: string[] = [];
  Object.defineProperty(window.navigator, "sendBeacon", {
    configurable: true,
    value: (address: string) => sent.push(address) > 0,
  });
  let visibility = "visible";
  Object.defineProperty(window.document, "visibilityState", {
    configurable: true,
    get: () => visibility,
  });
  const globals = {
    window,
    document: window.document,
    navigator: window.navigator,
    localStorage: window.localStorage,
    IS_REACT_ACT_ENVIRONMENT: true,
  };
  const old = new Map(
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
  t.mock.timers.enable({ apis: ["setTimeout"] });
  t.after(async () => {
    t.mock.timers.reset();
    await window.happyDOM.close();
    for (const [name, descriptor] of old) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else Reflect.deleteProperty(globalThis, name);
    }
  });

  const unmount = await mountBeacon(window, "home", "/雨后/100%");
  t.mock.timers.tick(3000);
  // Hiding the page restarts the five visible seconds.
  visibility = "hidden";
  window.document.dispatchEvent(new window.Event("visibilitychange"));
  t.mock.timers.tick(10000);
  assert.deepEqual(sent, []);
  visibility = "visible";
  window.document.dispatchEvent(new window.Event("visibilitychange"));
  t.mock.timers.tick(4999);
  assert.deepEqual(sent, []);
  t.mock.timers.tick(1);
  assert.deepEqual(sent, [
    "/api/public/community/spaces/home/views?route=%2F%E9%9B%A8%E5%90%8E%2F100%25",
  ]);
  await unmount();

  const again = await mountBeacon(window, "home", "/雨后/100%");
  t.mock.timers.tick(6000);
  assert.equal(sent.length, 1);
  await again();

  const other = await mountBeacon(window, "home", "/other");
  t.mock.timers.tick(5000);
  assert.equal(sent.length, 2);
  await other();
});
