import { NextRequest, NextResponse } from "next/server";
import { coverRoute } from "./lib/format";

export function proxy(request: NextRequest) {
  const nonce = Buffer.from(crypto.randomUUID()).toString("base64");
  const csp = [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}'${process.env.NODE_ENV === "development" ? " 'unsafe-eval'" : ""}`,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' blob:",
    "font-src 'self'",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "frame-ancestors 'none'",
    "form-action 'self'",
  ].join("; ");
  const headers = new Headers(request.headers);
  headers.set("x-nonce", nonce);
  headers.set("Content-Security-Policy", csp);
  const response = NextResponse.next({ request: { headers } });
  response.headers.set("Content-Security-Policy", csp);
  response.headers.set("X-Content-Type-Options", "nosniff");
  response.headers.set("Referrer-Policy", "strict-origin-when-cross-origin");
  // Pages carry a per-request nonce and are never reused; a header set here would replace the
  // caching that build-time files and the cover address declare for themselves.
  const path = request.nextUrl.pathname;
  if (!BUILD_FILES.has(path) && coverRoute(path) === undefined)
    response.headers.set("Cache-Control", "no-store");
  return response;
}
/** Files fixed at build time under paths this proxy matches: app/icon.svg and public/. */
const BUILD_FILES = new Set(["/icon.svg", "/share.png"]);
export const config = {
  matcher: ["/((?!api|_next/static|_next/image|favicon.ico).*)"],
};
