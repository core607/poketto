import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import { Window } from "happy-dom";
import type { ReactNode } from "react";
import { noticeAction, type CommunityNotification } from "../lib/community";

type Fetcher = (path: string, options?: RequestInit) => Promise<Response>;

async function mount(render: () => Promise<ReactNode>, fetcher: Fetcher) {
  const window = new Window({ url: "http://localhost/s/home/read/essay" });
  const globals = {
    window,
    document: window.document,
    navigator: window.navigator,
    HTMLElement: window.HTMLElement,
    HTMLDialogElement: window.HTMLDialogElement,
    HTMLTextAreaElement: window.HTMLTextAreaElement,
    HTMLInputElement: window.HTMLInputElement,
    Event: window.Event,
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
  const previousFetch = globalThis.fetch;
  globalThis.fetch = (async (
    input: RequestInfo | URL,
    options?: RequestInit,
  ) =>
    String(input) === "/api/auth/csrf"
      ? Response.json({ headerName: "X-CSRF", token: "fixture" })
      : fetcher(String(input), options)) as typeof fetch;
  const { act } = await import("react");
  const { createRoot } = await import("react-dom/client");
  const container = window.document.createElement("div");
  window.document.body.append(container);
  const root = createRoot(container as unknown as HTMLDivElement);
  await act(async () => root.render(await render()));
  const click = (label: string) =>
    act(async () => {
      const button = [...container.querySelectorAll("button")].find(
        (value) => value.textContent?.trim() === label,
      );
      assert.ok(button, label);
      button.click();
    });
  return {
    window,
    container,
    act,
    click,
    async cleanup() {
      await act(async () => root.unmount());
      globalThis.fetch = previousFetch;
      await window.happyDOM.close();
      for (const [name, descriptor] of old) {
        if (descriptor) Object.defineProperty(globalThis, name, descriptor);
        else Reflect.deleteProperty(globalThis, name);
      }
    },
  };
}

const BODY = "# 河流\r\n\r\n全长 30 公里。\r\n";

test("the digest covers the exact UTF-8 bytes of the served body", async () => {
  const { bodyDigest } = await import("../components/correction-proposal");
  assert.equal(
    await bodyDigest(BODY),
    "sha256:" + createHash("sha256").update(BODY, "utf8").digest("hex"),
  );
});

test("a reader proposes against the served body, keeps its line endings and can withdraw", async (t) => {
  const posted: unknown[] = [];
  let open = false;
  const ui = await mount(
    async () => {
      const { CorrectionProposal } =
        await import("../components/correction-proposal");
      return <CorrectionProposal space="home" route="/essay" body={BODY} />;
    },
    async (path, options) => {
      if (path === "/api/public/community/spaces/home")
        return Response.json({
          following: false,
          accountId: "reader",
          mayParticipate: true,
        });
      if (
        path ===
        "/api/auth/community/spaces/home/corrections/mine?route=%2Fessay"
      )
        return open
          ? Response.json({
              id: "c1",
              status: "OPEN",
              createdAt: "2026-09-24T00:00:00Z",
            })
          : new Response(null, { status: 204 });
      if (path === "/api/auth/community/spaces/home/corrections") {
        posted.push(JSON.parse(String(options?.body)));
        open = true;
        return Response.json({ id: "c1" });
      }
      if (path === "/api/auth/community/corrections/c1") {
        assert.equal(options?.method, "DELETE");
        open = false;
        return new Response(null, { status: 204 });
      }
      throw new Error("unexpected " + path);
    },
  );
  t.after(() => ui.cleanup());
  await ui.click("建议修改");
  const textarea = ui.container.querySelector("textarea");
  assert.ok(textarea);
  Object.getOwnPropertyDescriptor(
    ui.window.HTMLTextAreaElement.prototype,
    "value",
  )!.set!.call(textarea, "# 河流\n\n全长 32 公里。\n");
  await ui.act(async () =>
    textarea.dispatchEvent(new ui.window.Event("input", { bubbles: true })),
  );
  await ui.act(async () => {
    ui.container
      .querySelector("form")!
      .dispatchEvent(
        new ui.window.Event("submit", { bubbles: true, cancelable: true }),
      );
    await new Promise((resolve) => setTimeout(resolve, 20));
  });
  assert.deepEqual(posted, [
    {
      route: "/essay",
      baseDigest:
        "sha256:" + createHash("sha256").update(BODY, "utf8").digest("hex"),
      body: "# 河流\r\n\r\n全长 32 公里。\r\n",
      reason: "",
      credited: true,
    },
  ]);
  assert.match(ui.container.textContent!, /修改建议已提交/);
  await ui.click("撤回建议");
  await ui.act(async () => new Promise((resolve) => setTimeout(resolve, 20)));
  assert.match(ui.container.textContent!, /发现错字或不准确的地方？/);
});

test("a publishing member sees the line diff and accepting reports the result", async (t) => {
  const review = {
    id: "c1",
    position: 1,
    space: "home",
    route: "/essay",
    title: "河流",
    author: { accountId: "reader", displayName: "读者甲" },
    reason: "长度写错了",
    proposedBody: "全长 32 公里。\n",
    currentBody: "全长 30 公里。\n",
    stale: false,
    createdAt: "2026-09-24T00:00:00Z",
  };
  let items = [review];
  const accepted: string[] = [];
  const ui = await mount(
    async () => {
      const { ConfirmationProvider } =
        await import("../components/confirmation");
      const { CorrectionReview } =
        await import("../components/correction-review");
      return (
        <ConfirmationProvider>
          <CorrectionReview workspaceId="w1" />
        </ConfirmationProvider>
      );
    },
    async (path, options) => {
      if (path === "/api/auth/workspaces/w1/corrections")
        return Response.json({ items, nextBefore: null });
      assert.equal(options?.method, "POST");
      accepted.push(path);
      items = [];
      return Response.json({ result: "ACCEPTED" });
    },
  );
  t.after(() => ui.cleanup());
  await ui.act(async () => new Promise((resolve) => setTimeout(resolve, 20)));
  assert.match(ui.container.textContent!, /读者甲/);
  assert.match(ui.container.textContent!, /长度写错了/);
  assert.match(
    ui.container.querySelector(".history-line.removed")!.textContent!,
    /30/,
  );
  assert.match(
    ui.container.querySelector(".history-line.added")!.textContent!,
    /32/,
  );
  await ui.click("采纳");
  assert.deepEqual(accepted, []);
  await ui.click("采纳并提交");
  await ui.act(async () => new Promise((resolve) => setTimeout(resolve, 20)));
  assert.deepEqual(accepted, ["/api/auth/workspaces/w1/corrections/c1/accept"]);
  assert.match(ui.container.textContent!, /已采纳，《河流》的正文已更新。/);
  assert.match(ui.container.textContent!, /暂时没有待处理的修改建议/);
});

test("correction notifications say what happened", () => {
  const item = (event: CommunityNotification["event"]) =>
    ({
      article: { title: "河流" },
      event,
    }) as CommunityNotification;
  assert.equal(noticeAction(item("PROPOSED")), "对《河流》提了修改建议");
  assert.equal(noticeAction(item("ACCEPTED")), "采纳了你对《河流》的修改建议");
  assert.equal(noticeAction(item(null)), "回复了《河流》");
});
