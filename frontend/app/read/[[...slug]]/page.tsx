import { notFound, permanentRedirect } from "next/navigation";
import { article, defaultSpace, PublicApiError } from "../../../lib/public-api";
import { articleHref } from "../../../lib/format";
import {
  carryReadingSearch,
  type ReadingSearchParameters,
} from "../../../lib/search-return";

export async function generateMetadata({
  params,
}: {
  params: Promise<{ slug?: string[] }>;
}) {
  const { slug = [] } = await params;
  try {
    const value = await article("/" + slug.join("/"));
    return { title: value.title };
  } catch {
    return { title: "文章" };
  }
}

export default async function Article({
  params,
  searchParams,
}: {
  params: Promise<{ slug?: string[] }>;
  searchParams?: Promise<ReadingSearchParameters>;
}) {
  const { slug = [] } = await params;
  // Next's page catch-all segments are URI-encoded; metadata params are decoded.
  let segments: string[];
  try {
    segments = slug.map(decodeURIComponent);
  } catch {
    notFound();
  }
  if (
    segments.some(
      (segment) =>
        !segment ||
        segment === "." ||
        segment === ".." ||
        /[/\\\u0000-\u001f\u007f]/.test(segment),
    )
  )
    notFound();
  const route = "/" + segments.join("/");
  await article(route).catch((error) => {
    if (error instanceof PublicApiError && error.status === 404) notFound();
    throw error;
  });
  const space = await defaultSpace();
  permanentRedirect(
    articleHref(route, space.slug) +
      carryReadingSearch((await searchParams) ?? {}),
  );
}
