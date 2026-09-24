import type {
  Article,
  ArticlePage,
  TagPage,
  DiscoveryPage,
  SiteSearchPage,
} from "./types";
import { cache } from "react";

export class PublicApiError extends Error {
  constructor(public status: number) {
    super("内容暂时不可用");
  }
}
async function get<T>(path: string): Promise<T> {
  const base = process.env.POKETTO_API_BASE_URL ?? "http://127.0.0.1:8080";
  try {
    const response = await fetch(new URL(path, base), {
      cache: "no-store",
      signal: AbortSignal.timeout(10000),
      headers: { Accept: "application/json" },
    });
    if (!response.ok) throw new PublicApiError(response.status);
    return (await response.json()) as T;
  } catch (error) {
    if (error instanceof PublicApiError) throw error;
    throw new PublicApiError(503);
  }
}
/** All-time readers of a public article; null when unknown, so pages simply leave the count out. */
export async function articleViews(slug: string, route: string) {
  try {
    const { views } = await get<{ views: number }>(
      `/api/public/community/spaces/${encodeURIComponent(slug)}/views?` +
        new URLSearchParams({ route }),
    );
    return Number.isSafeInteger(views) && views >= 0 ? views : null;
  } catch {
    return null;
  }
}
/** Readers credited for accepted corrections of a public article; empty when unknown. */
export async function correctionCredits(slug: string, route: string) {
  try {
    const { names } = await get<{ names: string[] }>(
      `/api/public/community/spaces/${encodeURIComponent(slug)}/corrections/credits?` +
        new URLSearchParams({ route }),
    );
    return Array.isArray(names)
      ? names.filter((name) => typeof name === "string")
      : [];
  } catch {
    return [];
  }
}
/** An article's cover thumbnail from its stable address; null when the article has no cover. */
export async function spaceCover(slug: string, route: string) {
  const base = process.env.POKETTO_API_BASE_URL ?? "http://127.0.0.1:8080";
  let response: Response;
  try {
    response = await fetch(
      new URL(
        `/api/public/spaces/${encodeURIComponent(slug)}/cover?` +
          new URLSearchParams({ route }),
        base,
      ),
      { cache: "no-store", signal: AbortSignal.timeout(10000) },
    );
  } catch {
    throw new PublicApiError(503);
  }
  if (response.status === 204) return null;
  if (!response.ok) throw new PublicApiError(response.status);
  const type = response.headers.get("Content-Type") ?? "";
  if (!/^image\/(jpeg|png)$/.test(type)) throw new PublicApiError(503);
  return { type, bytes: await response.arrayBuffer() };
}
export function articles(parameters: Record<string, string> = {}) {
  return get<ArticlePage>(
    "/api/public/documents?" + new URLSearchParams(parameters),
  );
}
export function siteSearch(parameters: Record<string, string>) {
  return get<SiteSearchPage>(
    "/api/public/search?" + new URLSearchParams(parameters),
  );
}
export function discovery(parameters: Record<string, string> = {}) {
  return get<DiscoveryPage>(
    "/api/public/discovery?" + new URLSearchParams(parameters),
  );
}
export const article = cache(function article(route: string) {
  return get<Article>("/api/public/document?" + new URLSearchParams({ route }));
});
export type PublicSpace = {
  slug: string;
  displayName: string;
  /** Owner-written plain text, possibly empty; older servers omit it. */
  description?: string;
};
export const defaultSpace = cache(() =>
  get<PublicSpace>("/api/public/default-space"),
);
export const spaceInfo = cache((slug: string) =>
  get<PublicSpace>(`/api/public/spaces/${encodeURIComponent(slug)}`),
);
export const spaceArticle = cache((slug: string, route: string) =>
  get<Article>(
    `/api/public/spaces/${encodeURIComponent(slug)}/document?` +
      new URLSearchParams({ route }),
  ),
);
export function spaceArticles(
  slug: string,
  parameters: Record<string, string> = {},
) {
  return get<ArticlePage>(
    `/api/public/spaces/${encodeURIComponent(slug)}/documents?` +
      new URLSearchParams(parameters),
  );
}
export function spaceTags(
  slug: string,
  parameters: Record<string, string> = {},
) {
  return get<TagPage>(
    `/api/public/spaces/${encodeURIComponent(slug)}/tags?` +
      new URLSearchParams(parameters),
  );
}
export function tags(parameters: Record<string, string> = {}) {
  return get<TagPage>("/api/public/tags?" + new URLSearchParams(parameters));
}
export async function allArticles() {
  const first = await articles({ limit: "100" });
  const items = [...first.items];
  for (
    let offset = 100;
    offset < first.total && offset < 10000;
    offset += 100
  ) {
    const next = await articles({ limit: "100", offset: String(offset) });
    if (next.commit !== first.commit) throw new PublicApiError(503);
    items.push(...next.items);
  }
  return items;
}
export function publicOrigin() {
  if (!process.env.POKETTO_PUBLIC_URL) throw new PublicApiError(503);
  const url = new URL(process.env.POKETTO_PUBLIC_URL);
  if (!/^https?:$/.test(url.protocol) || url.username || url.password)
    throw new PublicApiError(503);
  return url.origin;
}

export function sitemapSpaces() {
  return get<string[]>("/api/public/sitemap");
}

export function spaceSitemap(slug: string) {
  return get<{
    slug: string;
    pages: { route: string; updatedAt: string }[];
  }>(`/api/public/spaces/${encodeURIComponent(slug)}/sitemap`);
}
