import assert from "node:assert/strict";
import test from "node:test";
import { Window } from "happy-dom";
import type { QaQuestion, QaChoice, QaReply } from "../lib/qa";

async function mount(fetcher: typeof fetch) {
  const window = new Window({ url: "http://localhost/ask" });
  const globals = {
    window,
    document: window.document,
    navigator: window.navigator,
    HTMLElement: window.HTMLElement,
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
  globalThis.fetch = fetcher;
  const { act } = await import("react");
  const { createRoot } = await import("react-dom/client");
  const { QaPanel } = await import("../components/qa-panel");
  const container = window.document.createElement("div");
  window.document.body.append(container);
  const root = createRoot(container as unknown as HTMLDivElement);
  await act(async () => root.render(<QaPanel />));
  return {
    window,
    container,
    act,
    async fill(label: string, value: string) {
      const field = container.querySelector(`textarea[aria-label="${label}"]`);
      assert.ok(field);
      Object.getOwnPropertyDescriptor(
        window.HTMLTextAreaElement.prototype,
        "value",
      )!.set!.call(field, value);
      await act(async () =>
        field.dispatchEvent(new window.Event("input", { bubbles: true })),
      );
    },
    async submit() {
      const form = container.querySelector("form");
      assert.ok(form);
      await act(async () =>
        form.dispatchEvent(
          new window.Event("submit", { bubbles: true, cancelable: true }),
        ),
      );
    },
    async click(label: string) {
      const button = [...container.querySelectorAll("button")].find(
        (value) => value.textContent === label,
      );
      assert.ok(button, label);
      await act(async () => button.click());
    },
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

const usage = {
  calls: 1,
  inputTokens: 100,
  outputTokens: 20,
  costUpperUsd: "0.000054",
  uncertain: false,
};

test("uncertain questions retry the same identity, clarification waits for a choice, and citations render as text", async (t) => {
  const writes: (QaQuestion | QaChoice)[] = [];
  const ui = await mount(async (input, options) => {
    if (String(input) === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    if (options?.method !== "POST")
      return Response.json({
        remaining: 4,
        dailyLimit: 5,
        resetsAt: "2026-10-10T00:00:00Z",
        runCostUpperUsd: "0.14",
      });
    const body = JSON.parse(String(options.body)) as QaQuestion | QaChoice;
    writes.push(body);
    if (writes.length === 1) throw new Error("Response lost after dispatch");
    const reply: QaReply = {
      requestId: body.requestId,
      status: "WAITING",
      code: "WAITING",
      revision: 1,
      paragraphs: [],
      clarification: {
        question: "需要哪一范围？",
        options: ["一篇文章", "跨文章整理"],
        expiresAt: "2026-10-09T12:10:00Z",
      },
      notice: "等待选择。",
      usage,
    };
    if (String(input) === "/api/qa/continue") {
      reply.status = "COMPLETED";
      reply.clarification = null;
      reply.paragraphs = [
        {
          text: "<img src=x onerror=alert(1)>文章提到了花园。",
          citations: [
            {
              reference: "paper/rain",
              title: "<script>paper</script>",
              url: "/s/paper/read/rain",
              quote: "Rain falls in the garden.",
            },
          ],
        },
      ];
    }
    return Response.json(reply);
  });
  t.after(() => ui.cleanup());
  assert.equal(writes.length, 0);
  await ui.fill("问题", "文章说了什么？");
  await ui.submit();
  assert.match(ui.container.textContent, /不会自动重发/);
  assert.equal(writes.length, 1);
  await ui.click("重试同一请求");
  assert.deepEqual(writes[1], writes[0]);
  assert.match(ui.container.textContent, /等待选择期间不调用模型/);
  assert.equal(
    ui.container.querySelectorAll("input[type=radio]:checked").length,
    0,
  );
  assert.equal(writes.length, 2);
  await ui.fill("补充范围", "跨文章整理");
  assert.equal(writes.length, 2);
  await ui.submit();
  assert.equal(writes.length, 3);
  assert.deepEqual(writes[2], {
    requestId: writes[0].requestId,
    revision: 1,
    answer: "跨文章整理",
  });
  assert.equal(ui.container.querySelectorAll("img,script").length, 0);
  assert.match(ui.container.textContent, /<img src=x onerror=alert\(1\)>/);
  assert.equal(
    ui.container.querySelector("a")?.getAttribute("href"),
    "/s/paper/read/rain",
  );
  assert.match(
    ui.container.querySelector("blockquote")!.textContent,
    /Rain falls/,
  );
});

test("non-creators see the eligibility boundary and cannot submit a question", async (t) => {
  let writes = 0;
  const ui = await mount(async (_input, options) => {
    if (options?.method === "POST") writes++;
    return Response.json({}, { status: 403 });
  });
  t.after(() => ui.cleanup());
  assert.match(ui.container.textContent, /仅向当前创作者和管理员开放/);
  assert.equal(ui.container.querySelector("textarea")?.disabled, true);
  assert.equal(writes, 0);
});
