import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer } from "node:http";
import test, { type TestContext } from "node:test";
import { Window } from "happy-dom";
import { renderToStaticMarkup } from "react-dom/server";
import Home from "../app/page";
import { DiscoveryCover } from "../components/discovery-cover";

test("discovery covers keep unsafe sources in the safe placeholder", () => {
  const valid = renderToStaticMarkup(
    <DiscoveryCover
      src="/api/public/assets/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
      href="/s/space/read/album"
      title="公开相册"
    />,
  );
  assert.match(valid, /<img[^>]+src="\/api\/public\/assets\/A+/);
  assert.match(valid, /href="\/s\/space\/read\/album"/);
  assert.match(valid, /aria-label="打开相册：公开相册"/);

  const unsafe = renderToStaticMarkup(
    <DiscoveryCover
      src="https://external.example/cover.jpg"
      href="/s/space/read/album"
      title="公开相册"
    />,
  );
  assert.doesNotMatch(unsafe, /<img/);
  assert.match(unsafe, /封面暂时不可用 · 打开相册/);
});

test("home keeps simultaneous album and collection labels on a discovery card", async (t: TestContext) => {
  const server = createServer((request, response) => {
    assert.equal(
      new URL(request.url!, "http://localhost").pathname,
      "/api/public/discovery",
    );
    response.setHeader("Content-Type", "application/json");
    response.end(
      JSON.stringify({
        batch: "batch-1",
        expiresAt: "2026-09-14T01:00:00Z",
        offset: 0,
        limit: 6,
        nextOffset: null,
        previousOffset: null,
        items: [
          {
            space: "space",
            spaceName: "公开空间",
            authorName: "作者",
            route: "/album",
            title: "相册合集",
            snippet: "一段公开摘要",
            tags: ["旅行"],
            createdAt: "2026-09-01T00:00:00Z",
            folderPage: true,
            album: true,
            collection: true,
            cover:
              "/api/public/assets/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
          },
        ],
      }),
    );
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const previous = process.env.POKETTO_API_BASE_URL;
  const address = server.address();
  assert.ok(address && typeof address !== "string");
  process.env.POKETTO_API_BASE_URL = `http://127.0.0.1:${address.port}`;
  t.after(() => {
    if (previous === undefined) delete process.env.POKETTO_API_BASE_URL;
    else process.env.POKETTO_API_BASE_URL = previous;
    server.closeAllConnections();
    server.close();
  });

  const html = renderToStaticMarkup(
    await Home({ searchParams: Promise.resolve({ batch: "batch-1" }) }),
  );
  assert.match(html, /相册 · 合集/);
  assert.match(html, /打开相册/);
  assert.match(html, /src="\/api\/public\/assets\/A+/);
});

test("a changed thumbnail grant restores the image after an earlier error", async (t: TestContext) => {
  const window = new Window({ url: "https://site.example/" });
  const globals = {
    window,
    document: window.document,
    navigator: window.navigator,
    HTMLElement: window.HTMLElement,
    HTMLImageElement: window.HTMLImageElement,
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
  const container = window.document.createElement("div");
  window.document.body.append(container);
  const root = createRoot(container as unknown as HTMLDivElement);
  t.after(async () => {
    await act(async () => root.unmount());
    await window.happyDOM.close();
    for (const [name, descriptor] of previous) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else Reflect.deleteProperty(globalThis, name);
    }
  });

  const render = async (src: string) =>
    act(async () =>
      root.render(
        <DiscoveryCover
          src={src}
          href="/s/space/read/album"
          title="公开相册"
        />,
      ),
    );
  const first =
    "/api/public/assets/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
  const second =
    "/api/public/assets/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
  await render(first);
  let image = container.querySelector("img");
  assert.ok(image);
  assert.equal(image.getAttribute("src"), first);

  await act(async () => image!.dispatchEvent(new window.Event("error")));
  assert.equal(container.querySelector("img"), null);
  assert.match(container.textContent ?? "", /封面暂时不可用/);

  await render(second);
  image = container.querySelector("img");
  assert.ok(image);
  assert.equal(image.getAttribute("src"), second);
});
