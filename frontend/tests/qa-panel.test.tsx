import assert from "node:assert/strict";
import test from "node:test";
import { Window } from "happy-dom";
import type { QaAllowance, QaQuestion, QaChoice, QaReply } from "../lib/qa";

async function mount(fetcher: typeof fetch) {
  const window = new Window({ url: "http://localhost/ask" });
  const globals = {
    window,
    document: window.document,
    navigator: window.navigator,
    HTMLElement: window.HTMLElement,
    HTMLTextAreaElement: window.HTMLTextAreaElement,
    HTMLInputElement: window.HTMLInputElement,
    FormData: window.FormData,
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

const allowance: QaAllowance = {
  remaining: 4,
  dailyLimit: 5,
  resetsAt: "2026-10-10T00:00:00Z",
  defaultProvider: "anthropic",
  models: [
    {
      provider: "anthropic",
      model: "claude-haiku-5-5",
      configured: true,
      runCostUpperUsd: "0.1",
    },
    {
      provider: "deepseek",
      model: "deepseek-flash",
      configured: true,
      runCostUpperUsd: "0.2",
    },
  ],
  anthropicBudget: {
    limitUsd: "20",
    spentUsd: "0",
    reservedUsd: "0",
    remainingUsd: "20",
    resetsAt: "2026-11-01T00:00:00Z",
  },
};
const selection = {
  requestedProvider: "anthropic",
  provider: "anthropic",
  model: "claude-haiku-5-5",
  fallbackReason: null,
};

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
    if (options?.method !== "POST") return Response.json(allowance);
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
      selection,
      activity: [],
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

test("successful login clears the earlier unauthorized notice and unlocks the question", async (t) => {
  let loggedIn = false;
  const ui = await mount(async (input) => {
    if (String(input) === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    if (String(input) === "/api/auth/identity/policy")
      return Response.json({ emailAvailable: false, googleAvailable: false });
    if (String(input) === "/api/auth/login") {
      loggedIn = true;
      return Response.json({});
    }
    return loggedIn
      ? Response.json(allowance)
      : Response.json({}, { status: 401 });
  });
  t.after(() => ui.cleanup());
  assert.match(ui.container.textContent, /登录信息无效/);
  await ui.click("登录以使用问答");
  const login = ui.container.querySelector('input[name="login"]');
  const password = ui.container.querySelector('input[name="password"]');
  assert.ok(login instanceof ui.window.HTMLInputElement);
  assert.ok(password instanceof ui.window.HTMLInputElement);
  login.value = "fixture";
  password.value = "fixture-password";
  const form = login.closest("form");
  assert.ok(form);
  await ui.act(async () =>
    form.dispatchEvent(
      new ui.window.Event("submit", { bubbles: true, cancelable: true }),
    ),
  );
  assert.equal(loggedIn, true);
  assert.doesNotMatch(ui.container.textContent, /登录信息无效/);
  const question = ui.container.querySelector('textarea[aria-label="问题"]');
  assert.ok(question instanceof ui.window.HTMLTextAreaElement);
  assert.equal(question.disabled, false);
});

test("refusal waits for an explicit new-provider request or editing without silently retrying", async (t) => {
  const writes: QaQuestion[] = [];
  const ui = await mount(async (input, options) => {
    if (String(input) === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    if (options?.method !== "POST") return Response.json(allowance);
    const body = JSON.parse(String(options.body)) as QaQuestion;
    writes.push(body);
    return Response.json({
      requestId: body.requestId,
      status: "FAILED",
      code: "MODEL_REFUSED",
      revision: 0,
      paragraphs: [],
      clarification: null,
      notice: "所选模型拒绝了这次请求。",
      usage,
      selection: { ...selection, provider: body.provider },
      activity: [],
    });
  });
  t.after(() => ui.cleanup());
  await ui.fill("问题", "Find public papers?");
  await ui.submit();
  assert.equal(writes.length, 1);
  assert.match(ui.container.textContent, /拒绝了这次请求/);
  await ui.click("重新编辑");
  assert.equal(writes.length, 1);
  assert.equal(
    ui.container.querySelector('textarea[aria-label="问题"]')?.textContent,
    "Find public papers?",
  );
  await ui.fill("问题", "Find garden papers?");
  await ui.submit();
  assert.equal(writes.length, 2);
  await ui.click("改用 DeepSeek");
  assert.equal(writes.length, 3);
  assert.equal(writes[2].provider, "deepseek");
  assert.equal(writes[2].question, "Find garden papers?");
  assert.notEqual(writes[2].requestId, writes[1].requestId);
  assert.equal(ui.container.querySelector("select")?.value, "deepseek");
  assert.equal(
    [...ui.container.querySelectorAll("button")].some(
      (button) => button.textContent === "改用 DeepSeek",
    ),
    false,
  );
});

test("read-only progress displays thinking and tool results without replaying the question", async (t) => {
  let finish: (response: Response) => void = () => {};
  let writes = 0;
  let reads = 0;
  let request: QaQuestion;
  const activity: QaReply["activity"] = [
    {
      id: 0,
      kind: "thinking",
      name: "deepseek-flash",
      state: "COMPLETED",
      input: "",
      output: "完整思考 <script>no</script>",
      elapsedMillis: 1250,
    },
    {
      id: 1,
      kind: "tool",
      name: "search",
      state: "RUNNING",
      input: '{"query":"garden"}',
      output: "",
      elapsedMillis: 0,
    },
  ];
  const fallback = {
    ...selection,
    provider: "deepseek",
    model: "deepseek-flash",
    fallbackReason: "ANTHROPIC_MONTHLY_BUDGET",
  };
  const ui = await mount(async (input, options) => {
    if (String(input) === "/api/auth/csrf")
      return Response.json({ headerName: "X-CSRF", token: "fixture" });
    if (options?.method === "POST") {
      writes++;
      request = JSON.parse(String(options.body));
      return new Promise<Response>((resolve) => {
        finish = resolve;
      });
    }
    if (String(input) === "/api/qa") return Response.json(allowance);
    reads++;
    return Response.json({
      requestId: request.requestId,
      status: "RUNNING",
      code: "RUNNING",
      revision: 0,
      paragraphs: [],
      notice: "Working",
      usage,
      selection: fallback,
      activity,
    });
  });
  t.after(() => ui.cleanup());
  assert.equal(ui.container.querySelector("select")?.value, "anthropic");
  assert.match(ui.container.textContent, /20.00/);
  await ui.fill("问题", "Garden papers?");
  await ui.submit();
  await ui.act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 1150));
  });
  assert.equal(writes, 1);
  assert.ok(reads > 0);
  assert.equal(request!.provider, "anthropic");
  assert.match(ui.container.textContent, /本次已使用 DeepSeek/);
  assert.match(ui.container.textContent, /完整思考 <script>no<\/script>/);
  assert.equal(ui.container.querySelectorAll("script").length, 0);
  assert.equal(ui.container.querySelectorAll("details.qa-step").length, 2);
  assert.match(ui.container.textContent, /搜索公开文章…/);
  await ui.act(async () =>
    finish(
      Response.json({
        requestId: request!.requestId,
        status: "COMPLETED",
        code: "OK",
        revision: 0,
        paragraphs: [],
        notice: "Finished",
        usage,
        selection: fallback,
        activity: [
          activity[0],
          { ...activity[0], id: 2, output: "" },
          {
            ...activity[1],
            state: "COMPLETED",
            output: '{"total":2}',
            elapsedMillis: 150,
          },
        ],
      }),
    ),
  );
  assert.match(ui.container.textContent, /"total": 2/);
  assert.match(
    ui.container.querySelectorAll("details.qa-step > summary")[1].textContent,
    /搜索「garden」 → 找到 2 篇/,
  );
  assert.equal(
    ui.container.querySelectorAll("details.qa-step")[1].hasAttribute("open"),
    false,
  );
  assert.equal(ui.container.querySelectorAll("details.qa-step").length, 2);
  assert.match(ui.container.textContent, /思考 1 轮 · 工具 1 次/);
  assert.doesNotMatch(ui.container.textContent, /没有返回可展示的思考/);
  const previous = reads;
  await ui.act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 1150));
  });
  assert.equal(reads, previous);
  assert.equal(writes, 1);
});
