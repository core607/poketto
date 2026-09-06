export function date(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "long",
    day: "numeric",
    timeZone: "UTC",
  }).format(new Date(value));
}
export function articleHref(route: string) {
  return (
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
    !(allowPrivate && value.startsWith("/api/admin/assets/"))
  )
    return undefined;
  const parsed = new URL(value, "https://placeholder.invalid");
  return parsed.pathname.startsWith("/api/public/assets/") ||
    (allowPrivate && parsed.pathname.startsWith("/api/admin/assets/"))
    ? value
    : undefined;
}
