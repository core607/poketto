import type { Article, ArticlePage, TagPage } from "./types";
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
export function articles(parameters: Record<string, string> = {}) {
  return get<ArticlePage>(
    "/api/public/documents?" + new URLSearchParams(parameters),
  );
}
export const article = cache(function article(route: string) {
  return get<Article>("/api/public/document?" + new URLSearchParams({ route }));
});
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
