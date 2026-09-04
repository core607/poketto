import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { articleHref, safeImage, safeLink } from "../lib/format";

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
  return (
    <div className="markdown">
      <ReactMarkdown
        skipHtml
        remarkPlugins={[remarkGfm]}
        urlTransform={(value) => value}
        components={{
          a({ href = "", children }) {
            const target = links[href]
              ? preview && links[href].startsWith("/admin?")
                ? safeLink(links[href])
                : articleHref(links[href])
              : safeLink(href);
            return target ? (
              <a href={target} rel="noreferrer noopener">
                {children}
              </a>
            ) : (
              <span>{children}</span>
            );
          },
          img({ src, alt }) {
            const source =
              typeof src === "string"
                ? safeImage(images[src], preview)
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
