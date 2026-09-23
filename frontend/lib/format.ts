export function date(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "long",
    day: "numeric",
    timeZone: "UTC",
  }).format(new Date(value));
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
export function articleHref(route: string, space?: string) {
  return (
    spaceHref(space) +
    "/read" +
    (route === "/" ? "" : route.split("/").map(encodeURIComponent).join("/"))
  );
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
