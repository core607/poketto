import assert from "node:assert/strict";
import test from "node:test";
import { Window } from "happy-dom";
import type { ReactNode } from "react";
import type { ArticleThread } from "../lib/community";

async function mount(render: () => Promise<ReactNode>, fetcher: typeof fetch) {
  const window = new Window({ url: "http://localhost/s/space/read/article" });
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
  const container = window.document.createElement("div");
  window.document.body.append(container);
  const root = createRoot(container as unknown as HTMLDivElement);
  await act(async () => root.render(await render()));
  return {
    window,
    container,
    act,
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

test("an uncertain comment submission retains its draft and retries the same request identity", async (t) => {
  const submissions: {
    requestId: string;
    body: string;
    parentId: string | null;
  }[] = [];
  let saved = 0;
  const ui = await mount(
    async () => {
      const { CommunityCommentForm } =
        await import("../components/community-comment-form");
      return (
        <CommunityCommentForm
          endpoint="/api/auth/community/comment"
          parentId={null}
          onSaved={() => saved++}
        />
      );
    },
    async (input, options) => {
      if (String(input) === "/api/auth/csrf")
        return Response.json({ headerName: "X-CSRF", token: "fixture" });
      submissions.push(JSON.parse(String(options?.body)));
      if (submissions.length === 1) throw new Error("reply lost after commit");
      return Response.json({ commentId: "confirmed" });
    },
  );
  t.after(() => ui.cleanup());
  const textarea = ui.container.querySelector("textarea");
  assert.ok(textarea);
  Object.getOwnPropertyDescriptor(
    ui.window.HTMLTextAreaElement.prototype,
    "value",
  )!.set!.call(textarea, "保留我的评论 😸");
  await ui.act(async () =>
    textarea.dispatchEvent(new ui.window.Event("input", { bubbles: true })),
  );
  const form = ui.container.querySelector("form");
  assert.ok(form);
  const submit = () =>
    form.dispatchEvent(
      new ui.window.Event("submit", { bubbles: true, cancelable: true }),
    );
  await ui.act(async () => {
    submit();
    await Promise.resolve();
  });
  assert.equal(textarea.value, "保留我的评论 😸");
  assert.equal(saved, 0);
  assert.match(ui.container.textContent!, /连接中断/);
  await ui.act(async () => {
    submit();
    await Promise.resolve();
  });
  assert.deepEqual(submissions[0], submissions[1]);
  assert.equal(saved, 1);
  assert.equal(textarea.value, "");
});

test("viewer comments remain plain text and existing bookmarks can be removed", async (t) => {
  const actions: { path: string; enabled: boolean }[] = [];
  const thread: ArticleThread = {
    likes: 1,
    liked: false,
    bookmarked: true,
    following: false,
    accountId: "viewer",
    mayParticipate: false,
    mayModerate: false,
    comments: {
      nextBefore: null,
      items: [
        {
          id: "comment",
          position: 1,
          parentId: null,
          author: { accountId: "author", displayName: "昵称" },
          body: '<img src=x onerror="alert(1)">',
          createdAt: "2026-09-23T00:00:00Z",
          deleted: false,
          replies: 0,
          mayDelete: false,
        },
      ],
    },
  };
  const ui = await mount(
    async () => {
      const { ArticleCommunity } =
        await import("../components/article-community");
      return <ArticleCommunity space="space" articleId="article" />;
    },
    async (input, options) => {
      const path = String(input);
      if (path === "/api/auth/csrf")
        return Response.json({ headerName: "X-CSRF", token: "fixture" });
      if (options?.method === "PUT") {
        actions.push({
          path,
          enabled: JSON.parse(String(options.body)).enabled,
        });
        return new Response(null, { status: 204 });
      }
      return Response.json(thread);
    },
  );
  t.after(() => ui.cleanup());
  assert.equal(ui.container.querySelector("img"), null);
  assert.match(ui.container.textContent!, /<img src=x/);
  assert.match(
    ui.container.textContent!,
    /评论显示账号昵称；文章署名由作者自行填写/,
  );
  assert.equal(ui.container.querySelector("textarea"), null);
  const buttons = [...ui.container.querySelectorAll("button")];
  const like = buttons.find((button) => button.textContent?.startsWith("点赞"));
  assert.ok(like?.disabled);
  const bookmark = buttons.find((button) =>
    button.textContent?.startsWith("已收藏"),
  );
  assert.ok(bookmark);
  assert.equal(bookmark.disabled, false);
  await ui.act(async () => bookmark.click());
  assert.deepEqual(actions, [
    {
      path: "/api/auth/community/spaces/space/articles/article/relations/BOOKMARK",
      enabled: false,
    },
  ]);
});

test("a downgraded account can remove an unavailable private bookmark without seeing its title", async (t) => {
  const actions: string[] = [];
  const ui = await mount(
    async () => {
      const { CommunityDashboard } =
        await import("../components/community-dashboard");
      return <CommunityDashboard />;
    },
    async (input, options) => {
      const path = String(input);
      if (path === "/api/auth/csrf")
        return Response.json({ headerName: "X-CSRF", token: "fixture" });
      if (path === "/api/auth/account")
        return Response.json({
          account: {
            accountId: "viewer",
            displayName: "Reader",
            group: "VIEWER",
            siteAdministrator: false,
          },
        });
      if (path.includes("/feed"))
        return Response.json({ items: [], nextCursor: null });
      if (options?.method === "DELETE") {
        actions.push(path);
        return new Response(null, { status: 204 });
      }
      if (path.includes("/bookmarks"))
        return Response.json({
          items: [{ position: 42, article: null }],
          nextBefore: null,
        });
      throw new Error(`Unexpected ${path}`);
    },
  );
  t.after(() => ui.cleanup());
  const tab = [...ui.container.querySelectorAll("button")].find(
    (item) => item.textContent === "私密收藏",
  );
  assert.ok(tab);
  await ui.act(async () => tab.click());
  assert.match(ui.container.textContent!, /收藏的文章目前不可用/);
  const remove = [...ui.container.querySelectorAll("button")].find(
    (item) => item.textContent === "取消收藏",
  );
  assert.ok(remove);
  await ui.act(async () => remove.click());
  assert.deepEqual(actions, ["/api/auth/community/bookmarks/42"]);
});
