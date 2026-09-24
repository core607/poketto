import { normalizeUri } from "micromark-util-sanitize-uri";
import ReactMarkdown from "react-markdown";
import rehypeHighlight from "rehype-highlight";
import rehypeSlug from "rehype-slug";
import remarkGfm from "remark-gfm";
import { articleHref, safeImage, safeLink } from "../lib/format";
import { HEADING_PREFIX, readingHeading } from "../lib/reading-heading";
import { MediaPlayer } from "./media-player";

export function Markdown({
  source,
  images = {},
  links = {},
  downloads = {},
  playback = {},
  preview = false,
  space,
  collection,
  pageTitle,
}: {
  source: string;
  images?: Record<string, string>;
  links?: Record<string, string>;
  downloads?: Record<string, string>;
  playback?: Record<string, string>;
  preview?: boolean;
  space?: string;
  collection?: { route: string; entries: { route: string }[] };
  pageTitle?: string;
}) {
  const resolvedImages = new Map(
    Object.entries(images).map(([authored, target]) => [
      normalizeUri(authored),
      target,
    ]),
  );
  const resolvedLinks = new Map(
    Object.entries(links).map(([authored, target]) => [
      normalizeUri(authored),
      resolvedLink(authored, target, preview, space, collection),
    ]),
  );
  const resolvedDownloads = new Map(
    Object.entries(downloads).map(([authored, target]) => [
      normalizeUri(authored),
      safeDownload(target, preview),
    ]),
  );
  const resolvedPlayback = new Map(
    Object.entries(playback).map(([authored, kind]) => [
      normalizeUri(authored),
      kind,
    ]),
  );
  return (
    <div className="markdown">
      <ReactMarkdown
        skipHtml
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[
          [rehypeSlug, { prefix: HEADING_PREFIX }],
          [readingHeading, { title: preview ? undefined : pageTitle }],
          // Only fenced blocks that name their language are highlighted; nothing is guessed.
          [rehypeHighlight, { detect: false }],
        ]}
        urlTransform={(value) => value}
        components={{
          a({ href = "", children, node }) {
            const footnote =
              node?.properties.dataFootnoteRef !== undefined ||
              node?.properties.dataFootnoteBackref !== undefined;
            const footnoteId =
              footnote &&
              typeof node?.properties.id === "string" &&
              node.properties.id.startsWith("user-content-")
                ? node.properties.id
                : undefined;
            const target = resolvedDownloads.has(href)
              ? resolvedDownloads.get(href)
              : resolvedLinks.has(href)
                ? resolvedLinks.get(href)
                : safeLink(
                    href.startsWith("#") && !footnote
                      ? headingFragment(href)
                      : href,
                  );
            const kind = resolvedPlayback.get(href);
            if (
              target &&
              resolvedDownloads.get(href) === target &&
              (kind === "audio" || kind === "video")
            ) {
              const address = new URL(target, "https://placeholder.invalid");
              address.searchParams.set("play", "true");
              const src = address.pathname + address.search + address.hash;
              return (
                <MediaPlayer key={src} kind={kind} src={src} download={target}>
                  {children}
                </MediaPlayer>
              );
            }
            return target ? (
              <a href={target} id={footnoteId} rel="noreferrer noopener">
                {children}
              </a>
            ) : (
              <span>{children}</span>
            );
          },
          img({ src, alt }) {
            const source =
              typeof src === "string"
                ? safeImage(resolvedImages.get(src), preview)
                : undefined;
            return source ? (
              <img
                src={source}
                alt={alt ?? ""}
                loading="lazy"
                decoding="async"
              />
            ) : (
              <span className="image-unavailable">
                ▧ {alt || "图片"} · 暂无可用预览
              </span>
            );
          },
        }}
      >
        {source}
      </ReactMarkdown>
    </div>
  );
}

function safeDownload(target: string, preview: boolean) {
  if (!safeLink(target)) return undefined;
  const expected = target.startsWith("/api/public/media?")
    ? "/api/public/media"
    : preview &&
        /^\/api\/(?:admin\/workspaces\/[0-9a-f-]{36}\/media|auth\/site\/workspaces\/[0-9a-f-]{36}\/review\/download)\?/.test(
          target,
        )
      ? target.slice(0, target.indexOf("?"))
      : undefined;
  if (!expected) return undefined;
  return new URL(target, "https://placeholder.invalid").pathname === expected
    ? target
    : undefined;
}

function resolvedLink(
  authored: string,
  target: string,
  preview: boolean,
  space?: string,
  collection?: { route: string; entries: { route: string }[] },
) {
  if (target.startsWith("#")) return safeLink(headingFragment(target));
  if (preview && target.startsWith("/admin?")) {
    const hash = target.indexOf("#");
    return safeLink(
      hash < 0
        ? target
        : target.slice(0, hash) + headingFragment(target.slice(hash)),
    );
  }
  if (!target.startsWith("/") || target.startsWith("//")) return undefined;
  // Only an authored fragment is separate from the raw repository route. A file
  // name can itself contain a hash or percent sign and must still be encoded.
  const hash = authored.indexOf("#");
  const fragment = hash < 0 ? "" : authored.slice(hash);
  const route =
    fragment && target.endsWith(fragment)
      ? target.slice(0, -fragment.length)
      : target;
  return safeLink(
    articleHref(route, space) +
      (collection?.entries.some((entry) => entry.route === route)
        ? "?" + new URLSearchParams({ collection: collection.route })
        : "") +
      (route !== target ? headingFragment(fragment) : ""),
  );
}

function headingFragment(fragment: string) {
  return fragment === "#"
    ? fragment
    : "#" + HEADING_PREFIX + normalizeUri(fragment.slice(1));
}
