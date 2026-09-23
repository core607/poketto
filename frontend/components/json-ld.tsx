import { publicOrigin } from "../lib/public-api";

/**
 * Structured data for search engines. It is a data block, not executed script, so the page CSP
 * does not apply to it; `<` is escaped so text in the data cannot close the element.
 */
export function JsonLd({ data }: { data: Record<string, unknown> }) {
  return (
    <script
      type="application/ld+json"
      dangerouslySetInnerHTML={{
        __html: JSON.stringify(data).replace(/</g, "\\u003c"),
      }}
    />
  );
}

/** A site path as the absolute URL structured data requires; undefined when no origin is configured. */
export function absoluteUrl(path: string) {
  try {
    return new URL(path, publicOrigin()).toString();
  } catch {
    return undefined;
  }
}
