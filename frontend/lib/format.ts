export function date(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "long",
    day: "numeric",
    timeZone: "UTC",
  }).format(new Date(value));
}
/** A saved moment on public pages, which date everything in UTC. */
export function dateTime(value: string) {
  return (
    new Intl.DateTimeFormat("zh-CN", {
      year: "numeric",
      month: "long",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
      timeZone: "UTC",
    }).format(new Date(value)) + " UTC"
  );
}
export function spaceHref(space?: string) {
  return space ? `/s/${encodeURIComponent(space)}` : "";
}
/** The feed link a space's pages declare; a page that sets its own alternates repeats it. */
export function spaceFeed(space: string, name: string) {
  return {
    "application/rss+xml": [
      { url: spaceHref(space) + "/rss.xml", title: name },
    ],
  };
}
/**
 * The route named by URI-encoded catch-all segments, as Next passes them to pages and route
 * handlers; null for malformed escapes, empty or dot segments, separators and control characters.
 */
export function routeFromSegments(slug: string[]) {
  let segments: string[];
  try {
    segments = slug.map(decodeURIComponent);
  } catch {
    return null;
  }
  const invalid = segments.some(
    (segment) =>
      !segment ||
      segment === "." ||
      segment === ".." ||
      /[/\\\u0000-\u001f\u007f]/.test(segment),
  );
  return invalid ? null : "/" + segments.join("/");
}
/** The site's 1200x630 link-preview image, for pages without an image of their own. */
export const SHARE_IMAGE = {
  url: "/share.png",
  width: 1200,
  height: 630,
  alt: "Poketto · 给想法一个留下来的地方",
};
/** The stable address of an article's cover image, beside its reading address. */
export function coverHref(route: string, space: string) {
  return spaceHref(space) + "/cover" + encodedRoute(route);
}
/**
 * The article route in a cover address path, the inverse of coverHref: undefined for any other
 * path and null for a malformed route. Only the cover route handler serves these paths, and the
 * proxy leaves their caching to it.
 */
export function coverRoute(pathname: string) {
  const match = /^\/s\/[^/]+\/cover(\/.*)?$/.exec(pathname);
  if (!match) return undefined;
  const rest = match[1] ?? "";
  return routeFromSegments(
    rest === "" || rest === "/" ? [] : rest.slice(1).split("/"),
  );
}
export function articleHref(route: string, space?: string) {
  return spaceHref(space) + "/read" + encodedRoute(route);
}
/** An article's public revision history. */
export function historyHref(route: string, space: string) {
  return spaceHref(space) + "/history" + encodedRoute(route);
}
/** A tag's listing, in the one spelling that links and its canonical share. */
export function tagHref(tag: string, space?: string) {
  return spaceHref(space) + "/tags?tag=" + encodeURIComponent(tag);
}
function encodedRoute(route: string) {
  return route === "/"
    ? ""
    : route.split("/").map(encodeURIComponent).join("/");
}
export function xml(value: string) {
  return value.replace(
    /[<>&"']/g,
    (character) =>
      ({
        "<": "&lt;",
        ">": "&gt;",
        "&": "&amp;",
        '"': "&quot;",
        "'": "&apos;",
      })[character]!,
  );
}
export function collectionArticleHref(
  route: string,
  space: string,
  collection: string,
) {
  return articleHref(route, space) + "?" + new URLSearchParams({ collection });
}
export function safeLink(value: string): string | undefined {
  if (!value || /[\u0000-\u0020\u007f\\]/.test(value) || value.startsWith("//"))
    return undefined;
  if (value.startsWith("#") || value.startsWith("/")) return value;
  try {
    const url = new URL(value);
    return ["https:", "http:", "mailto:"].includes(url.protocol)
      ? value
      : undefined;
  } catch {
    return undefined;
  }
}
export function safeImage(
  value: string | undefined,
  allowPrivate = false,
): string | undefined {
  if (!value || /[\u0000-\u0020\u007f\\]/.test(value)) return undefined;
  if (
    !value.startsWith("/api/public/assets/") &&
    !(
      allowPrivate &&
      /^\/api\/(?:admin\/workspaces\/[0-9a-f-]{36}\/assets\/images|auth\/site\/workspaces\/[0-9a-f-]{36}\/review\/images)\//.test(
        value,
      )
    )
  )
    return undefined;
  const parsed = new URL(value, "https://placeholder.invalid");
  return parsed.pathname.startsWith("/api/public/assets/") ||
    (allowPrivate &&
      /^\/api\/(?:admin\/workspaces\/[0-9a-f-]{36}\/assets\/images|auth\/site\/workspaces\/[0-9a-f-]{36}\/review\/images)\//.test(
        parsed.pathname,
      ))
    ? value
    : undefined;
}
