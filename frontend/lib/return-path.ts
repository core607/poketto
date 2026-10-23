/**
 * Returns a same-site page path a signed-in reader may be sent back to, or null. It mirrors the
 * server's Google return rule: a rooted path without authority, fragment, backslash, encoded
 * separators or dot segments, outside the API, MCP and well-known namespaces. The studio itself is
 * excluded because returning there is the default.
 */
export function returnPath(value: string | null | undefined) {
  if (!value || value.length > 2048) return null;
  if (
    !value.startsWith("/") ||
    value.startsWith("//") ||
    value.includes("\\") ||
    value.includes("#")
  )
    return null;
  let url: URL;
  try {
    url = new URL(value, "https://return.invalid");
  } catch {
    return null;
  }
  if (url.origin !== "https://return.invalid") return null;
  const path = value.split("?")[0];
  if (url.pathname !== path) return null;
  const lower = path.toLowerCase();
  if (/%2f|%5c|%2e/.test(lower)) return null;
  if (
    ["/api", "/mcp", "/.well-known", "/admin"].some(
      (prefix) => lower === prefix || lower.startsWith(prefix + "/"),
    )
  )
    return null;
  return value;
}

/** The sign-in address that brings the reader back to the given page afterwards. */
export function signInHref(current: string) {
  const back = returnPath(current);
  return back && back !== "/"
    ? "/admin?next=" + encodeURIComponent(back)
    : "/admin";
}
