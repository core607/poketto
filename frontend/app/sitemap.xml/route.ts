import { allArticles, publicOrigin } from "../../lib/public-api";
import { articleHref, xml } from "../../lib/format";
export const dynamic = "force-dynamic";
export async function GET() {
  try {
    const origin = publicOrigin();
    const items = await allArticles();
    return new Response(
      `<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"><url><loc>${xml(origin)}</loc></url>${items.map((item) => `<url><loc>${xml(origin + articleHref(item.route))}</loc><lastmod>${xml(item.updatedAt)}</lastmod></url>`).join("")}</urlset>`,
      {
        headers: {
          "Content-Type": "application/xml; charset=utf-8",
          "Cache-Control": "no-store",
        },
      },
    );
  } catch {
    return new Response("站点地图暂时不可用。", {
      status: 503,
      headers: { "Cache-Control": "no-store" },
    });
  }
}
