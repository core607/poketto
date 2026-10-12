import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer } from "node:http";
import test, { type TestContext } from "node:test";
import { Window } from "happy-dom";
import { renderToStaticMarkup } from "react-dom/server";
import Home from "../app/page";
import { DiscoveryCover } from "../components/discovery-cover";
import { coverFace, splitLead } from "../lib/cover-face";

const face = coverFace({
  space: "space",
  spaceName: "公开空间",
  tags: ["旅行"],
  lead: "",
});

test("discovery covers keep unsafe sources in the safe placeholder", () => {
  const valid = renderToStaticMarkup(
    <DiscoveryCover
      src="/api/public/assets/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
      href="/s/space/read/album"
      title="公开相册"
      face={face}
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
      face={face}
    />,
  );
  assert.doesNotMatch(unsafe, /<img/);
  assert.match(unsafe, /aria-label="打开相册：公开相册"/);
  assert.match(unsafe, /data-layout="seal"[^>]*>.*旅行.*公/);
});

test("a card without an image moves its opening sentence onto the cover", () => {
  assert.deepEqual(
    splitLead("TL;DR：总统称 AI 需要“强大且聪明（高 IQ！）的总统”。后文继续。"),
    {
      lead: "总统称 AI 需要“强大且聪明（高 IQ！）的总统”。",
      rest: "后文继续。",
    },
  );
  assert.deepEqual(splitLead("Version 2.5 ships. More follows"), {
    lead: "Version 2.5 ships.",
    rest: "More follows",
  });
  // A sentence too long for the cover keeps the whole snippet in the card body.
  const long = "长".repeat(120) + "。";
  assert.deepEqual(splitLead(long), { lead: "", rest: long });

  const poem = coverFace({
    space: "space",
    spaceName: "公开空间",
    tags: ["诗"],
    lead: "月亮落在窗台上。",
  });
  assert.equal(poem.vertical, true);
  assert.equal(poem.hue, face.hue);
  const html = renderToStaticMarkup(
    <DiscoveryCover
      src={null}
      href="/s/space/read/poem"
      title="诗"
      album={false}
      face={poem}
    />,
  );
  assert.match(html, /data-layout="vertical"/);
  assert.match(html, /月亮落在窗台上。/);
  assert.match(html, /aria-label="阅读：诗"/);
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
          face={face}
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
  assert.ok(container.querySelector('.cover-face[data-layout="seal"]'));

  await render(second);
  image = container.querySelector("img");
  assert.ok(image);
  assert.equal(image.getAttribute("src"), second);
});
