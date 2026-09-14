import {
  PublicApiError,
  publicOrigin,
  sitemapSpaces,
  spaceSitemap,
} from "../../lib/public-api";
import { articleHref, spaceHref, xml } from "../../lib/format";

export const dynamic = "force-dynamic";
const namespace = "http://www.sitemaps.org/schemas/sitemap/0.9";

function location(url: string) {
  if (url.length >= 2048) throw new PublicApiError(503);
  return `<loc>${xml(url)}</loc>`;
}

export async function GET(request: Request) {
  try {
    const origin = publicOrigin();
    const parameters = new URL(request.url).searchParams;
    const space = parameters.get("space");
    let body: string;
    if (space !== null) {
      const result = await spaceSitemap(space);
      if (result.pages.length > 49_999) throw new PublicApiError(503);
      body = `<urlset xmlns="${namespace}"><url>${location(origin + spaceHref(result.slug))}</url>${result.pages.map((page) => `<url>${location(origin + articleHref(page.route, result.slug))}<lastmod>${xml(page.updatedAt)}</lastmod></url>`).join("")}</urlset>`;
    } else if (parameters.get("site") === "1") {
      body = `<urlset xmlns="${namespace}"><url>${location(origin + "/")}</url></urlset>`;
    } else {
      const spaces = await sitemapSpaces();
      if (spaces.length > 49_999) throw new PublicApiError(503);
      const children = [
        `${origin}/sitemap.xml?site=1`,
        ...spaces.map(
          (slug) =>
            `${origin}/sitemap.xml?${new URLSearchParams({ space: slug })}`,
        ),
      ];
      body = `<sitemapindex xmlns="${namespace}">${children.map((url) => `<sitemap>${location(url)}</sitemap>`).join("")}</sitemapindex>`;
    }
    const output = `<?xml version="1.0" encoding="UTF-8"?>${body}`;
    if (Buffer.byteLength(output, "utf8") > 50 * 1024 * 1024)
      throw new PublicApiError(503);
    return new Response(output, {
      headers: {
        "Content-Type": "application/xml; charset=utf-8",
        "Cache-Control": "no-store",
      },
    });
  } catch (error) {
    const status =
      error instanceof PublicApiError && error.status === 404 ? 404 : 503;
    return new Response("站点地图暂时不可用。", {
      status,
      headers: { "Cache-Control": "no-store" },
    });
  }
}
