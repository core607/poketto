import { normalizeUri } from "micromark-util-sanitize-uri";
import ReactMarkdown from "react-markdown";
import rehypeSlug from "rehype-slug";
import remarkGfm from "remark-gfm";
import { articleHref, safeImage, safeLink } from "../lib/format";

const HEADING_PREFIX = "poketto-heading-";

export function Markdown({
  source,
  images = {},
  links = {},
  preview = false,
}: {
  source: string;
  images?: Record<string, string>;
  links?: Record<string, string>;
  preview?: boolean;
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
      resolvedLink(authored, target, preview),
    ]),
  );
  return (
    <div className="markdown">
      <ReactMarkdown
        skipHtml
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[[rehypeSlug, { prefix: HEADING_PREFIX }]]}
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
            const target = resolvedLinks.has(href)
              ? resolvedLinks.get(href)
              : safeLink(
                  href.startsWith("#") && !footnote
                    ? headingFragment(href)
                    : href,
                );
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

function resolvedLink(authored: string, target: string, preview: boolean) {
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
    articleHref(route) + (route !== target ? headingFragment(fragment) : ""),
  );
}

function headingFragment(fragment: string) {
  return fragment === "#"
    ? fragment
    : "#" + HEADING_PREFIX + normalizeUri(fragment.slice(1));
}
