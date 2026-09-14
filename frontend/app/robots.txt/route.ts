import { publicOrigin } from "../../lib/public-api";

export const dynamic = "force-dynamic";

export function GET() {
  const headers = {
    "Content-Type": "text/plain; charset=utf-8",
    "Cache-Control": "no-store",
  };
  try {
    return new Response(
      `User-agent: *\nAllow: /\nDisallow: /admin\nDisallow: /api/\nSitemap: ${publicOrigin()}/sitemap.xml\n`,
      { headers },
    );
  } catch {
    return new Response("抓取规则暂时不可用。", { status: 503, headers });
  }
}
