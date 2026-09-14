import assert from "node:assert/strict";
import test, { type TestContext } from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import { Window } from "happy-dom";
import { ArticleList } from "../components/articles";
import { SearchHighlight } from "../components/search-highlight";
import {
  carryReadingSearch,
  readingSearchReturn,
  searchArticleHref,
  searchPath,
} from "../lib/search-return";

const origin = "https://site.example";
const uuid = (value: number) =>
  `00000000-0000-4000-8000-${value.toString(16).padStart(12, "0")}`;

test("search highlighting is literal and keeps authored text escaped", () => {
  const html = renderToStaticMarkup(
    <SearchHighlight
      text={'a+b [literal] . <script>alert("x")</script>'}
      query="a+b"
    />,
  );
  assert.match(html, /<mark>a\+b<\/mark>/);
  assert.doesNotMatch(html, /<mark>\[literal\]<\/mark>/);
  assert.match(html, /&lt;script&gt;alert\(&quot;x&quot;\)&lt;\/script&gt;/);
  assert.doesNotMatch(html, /<script|onerror=/);

  const punctuation = renderToStaticMarkup(
    <SearchHighlight text="a.b [x] a+b" query="." />,
  );
  assert.equal((punctuation.match(/<mark>/g) ?? []).length, 1);
  assert.match(punctuation, /<mark>\.<\/mark>/);
});

test("search article cards highlight and carry only structured return fields", () => {
  const html = renderToStaticMarkup(
    <ArticleList
      page={{
        commit: "a",
        verifiedAt: "2026-09-14T00:00:00Z",
        expiresAt: "2026-09-14T01:00:00Z",
        total: 1,
        offset: 12,
        limit: 12,
        items: [
          {
            route: "/note",
            title: "a+b 标题",
            authorName: "署名",
            tags: [],
            createdAt: "2026-09-14T00:00:00Z",
            updatedAt: "2026-09-14T00:00:00Z",
            snippet: "正文 a+b <script>不执行</script>",
          },
        ],
      }}
      base="/search"
      parameters={{ query: "a+b" }}
      searchQuery="a+b"
    />,
  );
  assert.match(html, /<mark>a\+b<\/mark>/);
  assert.match(html, /data-search-result/);
  assert.match(html, /id="search-result-:/);
  assert.match(
    html,
    /href="\/read\/note\?searchQuery=a%2Bb&amp;searchOffset=12&amp;searchScope=site"/,
  );
  assert.match(html, /&lt;script&gt;不执行&lt;\/script&gt;/);
  assert.doesNotMatch(html, /<script/);
});

test("reading returns stay on the current scope and reject unstructured values", () => {
  const entry = uuid(1);
  const site = readingSearchReturn(
    {
      searchQuery: "a+b & <script>",
      searchOffset: "0012",
      searchScope: "site",
      searchEntry: entry,
    },
    "alpha",
  );
  assert.ok(site);
  const siteUrl = new URL(site, origin);
  assert.equal(siteUrl.origin, origin);
  assert.equal(siteUrl.pathname, "/search");
  assert.equal(siteUrl.searchParams.get("query"), "a+b & <script>");
  assert.equal(siteUrl.searchParams.get("offset"), "12");
  assert.equal(siteUrl.searchParams.get("resume"), entry);

  const scoped = readingSearchReturn(
    {
      searchQuery: "needle",
      searchOffset: "12",
      searchScope: "space",
      searchEntry: entry,
    },
    "alpha",
  );
  assert.ok(scoped);
  const scopedUrl = new URL(scoped, origin);
  assert.equal(scopedUrl.pathname, "/s/alpha/search");
  assert.equal(scopedUrl.searchParams.get("query"), "needle");

  for (const parameters of [
    { searchQuery: ["needle"], searchScope: "site" },
    { searchQuery: "needle", searchScope: ["site"] },
    { searchQuery: "needle", searchScope: "https://evil.example" },
    { searchQuery: "", searchScope: "site" },
    { searchQuery: "x".repeat(201), searchScope: "site" },
  ]) {
    assert.equal(readingSearchReturn(parameters, "alpha"), undefined);
  }
  const invalidEntry = new URL(
    readingSearchReturn(
      {
        searchQuery: "needle",
        searchScope: "site",
        searchEntry: "https://evil.example",
      },
      "alpha",
    )!,
    origin,
  );
  assert.equal(invalidEntry.searchParams.has("resume"), false);

  const carried = new URLSearchParams(
    carryReadingSearch({
      searchQuery: "a+b",
      searchOffset: "0012",
      searchScope: "space",
      searchEntry: entry,
    }),
  );
  assert.deepEqual(Object.fromEntries(carried), {
    searchQuery: "a+b",
    searchOffset: "12",
    searchScope: "space",
    searchEntry: entry,
  });
  assert.equal(
    carryReadingSearch({
      searchQuery: "needle",
      searchScope: "https://evil.example",
    }),
    "",
  );

  const article = new URL(
    searchArticleHref("/s/alpha/read/note", {
      query: "a+b",
      offset: 12,
      space: "alpha",
    }),
    origin,
  );
  assert.equal(article.origin, origin);
  assert.equal(article.pathname, "/s/alpha/read/note");
  assert.equal(article.searchParams.get("searchQuery"), "a+b");
  assert.equal(article.searchParams.get("searchOffset"), "12");
  assert.equal(article.searchParams.get("searchScope"), "space");
  assert.equal(article.searchParams.has("return"), false);
});

async function fixture(t: TestContext, url: string) {
  const window = new Window({ url });
  const nextId = { value: 100 };
  const globals = {
    window,
    document: window.document,
    navigator: window.navigator,
    sessionStorage: window.sessionStorage,
    HTMLElement: window.HTMLElement,
    Element: window.Element,
    HTMLAnchorElement: window.HTMLAnchorElement,
    Event: window.Event,
    MouseEvent: window.MouseEvent,
    IS_REACT_ACT_ENVIRONMENT: true,
    crypto: { randomUUID: () => uuid(nextId.value++) },
    requestAnimationFrame: (callback: FrameRequestCallback) => {
      callback(0);
      return 0;
    },
    cancelAnimationFrame: () => {},
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
  let scrolledTo: number | undefined;
  window.scrollTo = ((_x: number, value: number) => {
    scrolledTo = value;
  }) as typeof window.scrollTo;
  const { act } = await import("react");
  const { createRoot } = await import("react-dom/client");
  const { SearchResults } = await import("../components/search-results");
  const rawContainer = window.document.createElement("div");
  window.document.body.append(rawContainer);
  const container = rawContainer as unknown as HTMLDivElement;
  const root = createRoot(container);
  t.after(async () => {
    await act(async () => root.unmount());
    await window.happyDOM.close();
    for (const [name, value] of previous) {
      if (value) Object.defineProperty(globalThis, name, value);
      else Reflect.deleteProperty(globalThis, name);
    }
  });
  const mount = async (
    path: string,
    id = "search-result-alpha:%2Fnote",
    href = "/s/alpha/read/note",
  ) =>
    act(async () =>
      root.render(
        <SearchResults path={path}>
          <article className="article-card" id={id}>
            <h2>
              <a data-search-result href={href}>
                结果
              </a>
            </h2>
          </article>
        </SearchResults>,
      ),
    );
  const click = async () =>
    act(async () => {
      const link = container.querySelector<HTMLAnchorElement>(
        "a[data-search-result]",
      );
      assert.ok(link);
      link.dispatchEvent(
        new MouseEvent("click", { bubbles: true, cancelable: true }),
      );
    });
  return {
    window,
    container,
    mount,
    click,
    get scrolledTo() {
      return scrolledTo;
    },
  };
}

test("history owns a result entry and tab storage remains capped at 32", async (t) => {
  const path = searchPath({ query: "needle", offset: 12, space: "alpha" });
  const f = await fixture(t, `${origin}${path}`);
  const oldEntries = Array.from({ length: 40 }, (_, index) => ({
    id: uuid(index),
    path,
    anchor: "search-result-alpha:%2Fold-" + index,
    scrollY: index,
  }));
  f.window.sessionStorage.setItem(
    "poketto:search-returns",
    JSON.stringify(oldEntries),
  );
  Object.defineProperty(f.window, "scrollY", {
    configurable: true,
    value: 321,
  });
  await f.mount(path);
  await f.click();

  const saved = (
    f.window.history.state as {
      pokettoSearchPosition?: {
        id: string;
        path: string;
        anchor: string;
        scrollY: number;
      };
    } | null
  )?.pokettoSearchPosition;
  assert.ok(saved);
  assert.equal(saved.path, path);
  assert.equal(saved.anchor, "search-result-alpha:%2Fnote");
  assert.equal(saved.scrollY, 321);
  assert.equal(
    new URL(f.container.querySelector("a")!.href).searchParams.get(
      "searchEntry",
    ),
    saved.id,
  );
  const entries = JSON.parse(
    f.window.sessionStorage.getItem("poketto:search-returns")!,
  );
  assert.equal(entries.length, 32);
  assert.equal(entries.at(-1).id, saved.id);
});

test("history and explicit return restore only the matching canonical query and page", async (t) => {
  const path = searchPath({ query: "needle", offset: 12, space: "alpha" });
  const entry = uuid(1);
  const f = await fixture(t, `${origin}${path}&resume=${entry}`);
  const focusCalls: unknown[] = [];
  await f.mount(path);
  const headingLink = f.container.querySelector<HTMLAnchorElement>("h2 a")!;
  headingLink.focus = ((options?: FocusOptions) => {
    focusCalls.push(options);
  }) as typeof headingLink.focus;
  f.window.sessionStorage.setItem(
    "poketto:search-returns",
    JSON.stringify([
      { id: entry, path, anchor: "search-result-alpha:%2Fnote", scrollY: 654 },
    ]),
  );
  f.window.dispatchEvent(new f.window.Event("pageshow"));
  assert.equal(f.scrolledTo, 654);
  assert.equal(focusCalls.length, 1);
});

test("browser history restores the matching result position on Back", async (t) => {
  const path = searchPath({ query: "needle", offset: 12, space: "alpha" });
  const entry = uuid(2);
  const f = await fixture(t, `${origin}${path}`);
  const focusCalls: unknown[] = [];
  f.window.HTMLAnchorElement.prototype.focus = ((options?: FocusOptions) => {
    focusCalls.push(options);
  }) as typeof f.window.HTMLAnchorElement.prototype.focus;
  f.window.history.replaceState(
    {
      pokettoSearchPosition: {
        id: entry,
        path,
        anchor: "search-result-alpha:%2Fnote",
        scrollY: 432,
      },
    },
    "",
    path,
  );
  await f.mount(path);
  assert.equal(f.scrolledTo, 432);
  assert.equal(focusCalls.length, 1);
});

test("explicit return ignores an entry from another canonical query or page", async (t) => {
  const path = searchPath({ query: "needle", offset: 12, space: "alpha" });
  const entry = uuid(1);
  const f = await fixture(t, `${origin}${path}&resume=${entry}`);
  f.window.sessionStorage.setItem(
    "poketto:search-returns",
    JSON.stringify([
      {
        id: entry,
        path: searchPath({ query: "other", offset: 12, space: "alpha" }),
        anchor: "search-result-alpha:%2Fnote",
        scrollY: 987,
      },
    ]),
  );
  await f.mount(path);
  assert.equal(f.scrolledTo, undefined);
});

test("same-origin result links still work when session storage is unavailable", async (t) => {
  const path = searchPath({ query: "needle", offset: 0 });
  const f = await fixture(t, `${origin}${path}`);
  Object.defineProperty(f.window.sessionStorage, "getItem", {
    configurable: true,
    value: () => {
      throw new Error("storage disabled");
    },
  });
  Object.defineProperty(f.window.sessionStorage, "setItem", {
    configurable: true,
    value: () => {
      throw new Error("storage disabled");
    },
  });
  await f.mount(path);
  await assert.doesNotReject(f.click());
  const link = f.container.querySelector<HTMLAnchorElement>("a")!;
  assert.equal(new URL(link.href).origin, origin);
  assert.equal(new URL(link.href).pathname, "/s/alpha/read/note");
  assert.ok(new URL(link.href).searchParams.get("searchEntry"));
  assert.ok(
    (f.window.history.state as { pokettoSearchPosition?: unknown } | null)
      ?.pokettoSearchPosition,
  );
});

test("cross-origin result destinations are never decorated with a return entry", async (t) => {
  const path = searchPath({ query: "needle", offset: 0 });
  const f = await fixture(t, `${origin}${path}`);
  await f.mount(
    path,
    "search-result-site:%2Fnote",
    "https://evil.example/private",
  );
  await f.click();
  const link = f.container.querySelector<HTMLAnchorElement>("a")!;
  assert.equal(link.href, "https://evil.example/private");
  assert.equal(
    (f.window.history.state as { pokettoSearchPosition?: unknown } | null)
      ?.pokettoSearchPosition,
    undefined,
  );
});
