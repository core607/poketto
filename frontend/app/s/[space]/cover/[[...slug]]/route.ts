import { PublicApiError, spaceCover } from "../../../../../lib/public-api";
import { routeFromSegments } from "../../../../../lib/format";

export const dynamic = "force-dynamic";

// Link previews and search engines may keep a cover briefly; withdrawal still answers 404 here.
const CACHE = "public, max-age=300";

/**
 * An article's cover at an address that does not expire, beside its reading address. An article
 * without a cover redirects to the site's share image. The route is read from the request path,
 * which is always URI-encoded, so decoding follows the reading page exactly.
 */
export async function GET(
  request: Request,
  { params }: { params: Promise<{ space: string }> },
) {
  const { space } = await params;
  const marker = `/s/${encodeURIComponent(space)}/cover`;
  const path = new URL(request.url).pathname;
  const rest = path.startsWith(marker) ? path.slice(marker.length) : null;
  const route =
    rest === null
      ? null
      : routeFromSegments(
          rest === "" || rest === "/" ? [] : rest.slice(1).split("/"),
        );
  if (route === null) return missing();
  try {
    const cover = await spaceCover(space, route);
    if (cover === null)
      return new Response(null, {
        status: 307,
        headers: { Location: "/share.png", "Cache-Control": CACHE },
      });
    return new Response(cover.bytes, {
      headers: {
        "Content-Type": cover.type,
        "Cache-Control": CACHE,
        "X-Content-Type-Options": "nosniff",
      },
    });
  } catch (error) {
    if (error instanceof PublicApiError && error.status === 404)
      return missing();
    return new Response("封面暂时不可用。", {
      status: 503,
      headers: { "Cache-Control": "no-store" },
    });
  }
}

function missing() {
  return new Response("没有这张封面。", {
    status: 404,
    headers: { "Cache-Control": "no-store" },
  });
}
