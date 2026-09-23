import { NextRequest, NextResponse } from "next/server";

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
  // caching that the cover address and the share image declare for themselves.
  if (!OWN_CACHING.test(request.nextUrl.pathname))
    response.headers.set("Cache-Control", "no-store");
  return response;
}
const OWN_CACHING = /^\/(?:share\.png$|s\/[^/]+\/cover(?:\/|$))/;
export const config = {
  matcher: ["/((?!api|_next/static|_next/image|favicon.ico).*)"],
};
