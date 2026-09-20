import assert from "node:assert/strict";
import test from "node:test";
import { Window } from "happy-dom";
import { renderToStaticMarkup } from "react-dom/server";
import { Markdown } from "../components/markdown";
import { safeImage } from "../lib/format";

const workspace = "11111111-1111-4111-8111-111111111111";
const base = `/api/auth/site/workspaces/${workspace}/review`;

test("review images and downloads are allowed only in authenticated previews", () => {
  const image = `${base}/images/${"a".repeat(43)}`;
  const download = `${base}/download?route=%2Fnote&path=public%2Fsource.pdf&commit=abc`;
  assert.equal(safeImage(image), undefined);
  assert.equal(safeImage(image, true), image);
  assert.equal(
    safeImage(`${base}/images/../../../../private.png`, true),
    undefined,
  );
  const props = {
    source: "![Review](picture.png) [Download](source.pdf)",
    images: { "picture.png": image },
    downloads: { "source.pdf": download },
  };
  const review = renderToStaticMarkup(<Markdown {...props} preview />);
  assert.match(review, /review\/images/);
  assert.match(review, /review\/download/);
  const publicView = renderToStaticMarkup(<Markdown {...props} />);
  assert.doesNotMatch(publicView, /<img|review\/download/);
});

test("site account review selects an owned space and reads its bounded public document", async (t) => {
  const window = new Window({ url: "https://site.example/admin" });
  const globals = {
    window,
    document: window.document,
    navigator: window.navigator,
    HTMLElement: window.HTMLElement,
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
  const { SiteAccountSpaces, PublicationRestrictions } =
    await import("../components/site-review");
  const container = window.document.createElement("div");
  window.document.body.append(container);
  const root = createRoot(container as unknown as HTMLDivElement);
  const oldFetch = globalThis.fetch;
  t.after(async () => {
    await act(async () => root.unmount());
    globalThis.fetch = oldFetch;
    await window.happyDOM.close();
    for (const [name, descriptor] of previous) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else Reflect.deleteProperty(globalThis, name);
    }
  });
  const paths: string[] = [];
  globalThis.fetch = async (input) => {
    const path = String(input);
    paths.push(path);
    const page = (items: unknown[]) =>
      Response.json({ items, total: items.length, offset: 0, limit: 30 });
    if (path.startsWith("/api/auth/site/accounts/author/workspaces?"))
      return page([
        {
          workspaceId: workspace,
          displayName: "受限空间",
          enabled: true,
          eligible: false,
        },
      ]);
    if (path.startsWith(base + "?"))
      return page([
        { route: "/note", path: "public/note.md", title: "当前文章" },
      ]);
    if (path === base + "/document?route=%2Fnote")
      return Response.json({
        title: "当前文章",
        path: "public/note.md",
        media: {
          body: "公开正文 ![Picture](picture.png)",
          images: { "picture.png": `${base}/images/${"a".repeat(43)}` },
          downloads: {},
          gallery: [],
        },
      });
    if (path.includes("/publication/restrictions?"))
      return page([{ ownerName: "作者", reason: "请修订文章中的个人信息" }]);
    throw new Error("Unexpected request " + path);
  };
  await act(async () => root.render(<SiteAccountSpaces accountId="author" />));
  assert.match(container.textContent, /展示受限/);
  await act(async () =>
    [...container.querySelectorAll("button")]
      .find((button) => button.textContent === "审阅 受限空间")!
      .click(),
  );
  assert.match(container.textContent, /私有文件和历史版本不在此范围/);
  await act(async () =>
    [...container.querySelectorAll("button")]
      .find((button) => button.textContent === "当前文章")!
      .click(),
  );
  assert.match(container.textContent, /公开正文/);
  assert.equal(
    container.querySelector("img")?.getAttribute("src"),
    `${base}/images/${"a".repeat(43)}`,
  );
  assert.ok(paths.includes(base + "/document?route=%2Fnote"));
  await act(async () =>
    root.render(
      <PublicationRestrictions
        base={`/api/auth/workspaces/${workspace}/publication`}
      />,
    ),
  );
  assert.match(container.textContent, /作者：请修订文章中的个人信息/);
});
