import assert from "node:assert/strict";
import test from "node:test";
import {
  Window,
  type HTMLButtonElement,
  type HTMLInputElement,
} from "happy-dom";
import { scopedRoot, workspaceId } from "./workspace-fixture";

test("filename search pins pagination to the returned commit and ignores stale responses", async (t) => {
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
  const { FilenameSearch } = await import("../components/filename-search");
  const container = window.document.createElement("div");
  window.document.body.append(container);
  const root = scopedRoot(createRoot(container as unknown as HTMLDivElement));
  const previousFetch = globalThis.fetch;
  const requests: URL[] = [];
  const opened: string[] = [];
  let releaseStale!: (response: Response) => void;
  const staleResponse = new Promise<Response>((resolve) => {
    releaseStale = resolve;
  });
  globalThis.fetch = async (input) => {
    const url = new URL(String(input), "http://localhost");
    requests.push(url);
    assert.equal(
      url.pathname,
      `/api/admin/workspaces/${workspaceId}/repository/filenames`,
    );
    const query = url.searchParams.get("query");
    if (query === "stale") return staleResponse;
    const offset = Number(url.searchParams.get("offset"));
    return Response.json({
      commit: "commit-1",
      paths: offset === 0 ? ["public/name.md"] : ["private/name.md"],
      total: 51,
      offset,
      limit: 50,
    });
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

  await act(async () =>
    root.render(
      <FilenameSearch
        busy={false}
        commit="commit-1"
        onOpen={(path) => opened.push(path)}
      />,
    ),
  );
  const input = container.querySelector<HTMLInputElement>("input")!;
  Object.getOwnPropertyDescriptor(
    window.HTMLInputElement.prototype,
    "value",
  )!.set!.call(input, "name");
  await act(async () => {
    input.dispatchEvent(new window.Event("input", { bubbles: true }));
  });
  await act(async () => {
    container
      .querySelector("form")!
      .dispatchEvent(
        new window.Event("submit", { bubbles: true, cancelable: true }),
      );
  });
  assert.equal(requests[0]?.searchParams.get("query"), "name");
  assert.equal(requests[0]?.searchParams.get("offset"), "0");
  assert.match(container.textContent ?? "", /public\/name\.md/);
  assert.equal(container.querySelectorAll("mark").length, 1);

  await act(async () =>
    container
      .querySelector<HTMLButtonElement>(".filename-pagination button")!
      .click(),
  );
  assert.equal(requests[1]?.searchParams.get("offset"), "50");
  assert.equal(requests[1]?.searchParams.get("commit"), "commit-1");
  assert.match(container.textContent ?? "", /private\/name\.md/);
  await act(async () =>
    [...container.querySelectorAll(".private-results > button")]
      .find((button) => button.textContent?.includes("private/name.md"))
      ?.dispatchEvent(new window.Event("click", { bubbles: true })),
  );
  assert.deepEqual(opened, ["private/name.md"]);

  await act(async () =>
    root.render(
      <FilenameSearch
        busy={false}
        commit="commit-2"
        onOpen={(path) => opened.push(path)}
      />,
    ),
  );
  const staleInput = container.querySelector<HTMLInputElement>("input")!;
  Object.getOwnPropertyDescriptor(
    window.HTMLInputElement.prototype,
    "value",
  )!.set!.call(staleInput, "stale");
  await act(async () => {
    staleInput.dispatchEvent(new window.Event("input", { bubbles: true }));
  });
  await act(async () => {
    container
      .querySelector("form")!
      .dispatchEvent(
        new window.Event("submit", { bubbles: true, cancelable: true }),
      );
  });
  await act(async () =>
    root.render(
      <FilenameSearch
        busy={false}
        commit="commit-3"
        onOpen={(path) => opened.push(path)}
      />,
    ),
  );
  releaseStale(
    Response.json({
      commit: "commit-2",
      paths: ["stale.md"],
      total: 1,
      offset: 0,
      limit: 50,
    }),
  );
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
  assert.doesNotMatch(container.textContent ?? "", /stale\.md/);
});
