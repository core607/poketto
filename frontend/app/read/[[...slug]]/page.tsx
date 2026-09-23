import { notFound, redirect } from "next/navigation";
import { article, defaultSpace, PublicApiError } from "../../../lib/public-api";
import { articleHref, routeFromSegments } from "../../../lib/format";
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
  const route = routeFromSegments(slug);
  if (route === null) notFound();
  await article(route).catch((error) => {
    if (error instanceof PublicApiError && error.status === 404) notFound();
    throw error;
  });
  const space = await defaultSpace();
  // The target follows the current default space, so the redirect stays temporary.
  redirect(
    articleHref(route, space.slug) +
      carryReadingSearch((await searchParams) ?? {}),
  );
}
