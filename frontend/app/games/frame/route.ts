import { NextRequest } from "next/server";

export function GET(request: NextRequest) {
  // Standalone Next.js uses its internal listener in nextUrl behind the gateway.
  // Only the requested host and forwarded scheme belong in the browser's policy.
  const host = request.headers.get("host") ?? request.nextUrl.host;
  const scheme =
    request.headers.get("x-forwarded-proto") ??
    request.nextUrl.protocol.replace(":", "");
  let origin: string;
  try {
    const publicUrl = new URL(`${scheme}://${host}`);
    if (
      !["http:", "https:"].includes(publicUrl.protocol) ||
      publicUrl.username ||
      publicUrl.password ||
      publicUrl.pathname !== "/" ||
      publicUrl.search ||
      publicUrl.hash
    ) {
      return new Response("Invalid public origin", { status: 400 });
    }
    origin = publicUrl.origin;
  } catch {
    return new Response("Invalid public origin", { status: 400 });
  }
  const csp = [
    "default-src 'none'",
    `script-src blob: data: ${origin}/games/frame.mjs ${origin}/games/runtime.mjs`,
    "worker-src data:",
    "connect-src 'none'",
    "img-src data: blob:",
    "style-src 'unsafe-inline'",
    "base-uri 'none'",
    "form-action 'none'",
    "frame-ancestors 'self'",
  ].join("; ");
  return new Response(
    `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>口袋小游戏</title><style>body{margin:0;padding:1rem;font:16px/1.6 system-ui;color:#27251f;background:#faf8f1}p{white-space:pre-wrap}button{font:inherit;margin:.3rem;padding:.5rem 1rem;border:1px solid #bbb5a3;border-radius:.6rem;background:white;cursor:pointer}img{max-width:100%;max-height:20rem}#status{min-height:1.5em}</style><main><h2 id="title">口袋小游戏</h2><div id="scene"></div><div id="actions"></div><p id="status" role="status">等待载入…</p></main><script type="module" crossorigin="anonymous" src="/games/frame.mjs"></script></html>`,
    {
      headers: {
        "Content-Type": "text/html; charset=utf-8",
        "Content-Security-Policy": csp,
        "X-Content-Type-Options": "nosniff",
        "Cache-Control": "no-store",
        "Referrer-Policy": "no-referrer",
      },
    },
  );
}
