import { spaceHref } from "./format";
import { pageOffset } from "./pagination";

export type SearchContext = {
  query: string;
  offset: number;
  space?: string;
};

export type ReadingSearchParameters = {
  searchQuery?: string | string[];
  searchOffset?: string | string[];
  searchScope?: string | string[];
  searchEntry?: string | string[];
};

export function searchPath(context: SearchContext) {
  return (
    spaceHref(context.space) +
    "/search?" +
    new URLSearchParams({
      query: context.query,
      offset: String(context.offset),
    })
  );
}

export function searchArticleHref(href: string, context: SearchContext) {
  return (
    href +
    "?" +
    new URLSearchParams({
      searchQuery: context.query,
      searchOffset: String(context.offset),
      searchScope: context.space ? "space" : "site",
    })
  );
}

export function searchEntry(value: unknown): string | undefined {
  return typeof value === "string" &&
    /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
    ? value
    : undefined;
}

export function readingSearchReturn(
  parameters: ReadingSearchParameters,
  space: string,
) {
  if (
    typeof parameters.searchQuery !== "string" ||
    !parameters.searchQuery ||
    parameters.searchQuery.length > 200 ||
    (parameters.searchScope !== "site" && parameters.searchScope !== "space")
  )
    return undefined;
  const context: SearchContext = {
    query: parameters.searchQuery,
    offset: Number(pageOffset(parameters.searchOffset)),
    ...(parameters.searchScope === "space" ? { space } : {}),
  };
  const entry = searchEntry(parameters.searchEntry);
  return searchPath(context) + (entry ? "&resume=" + entry : "");
}

export function carryReadingSearch(parameters: ReadingSearchParameters) {
  const query = new URLSearchParams();
  if (
    typeof parameters.searchQuery === "string" &&
    parameters.searchQuery.length > 0 &&
    parameters.searchQuery.length <= 200 &&
    (parameters.searchScope === "site" || parameters.searchScope === "space")
  ) {
    query.set("searchQuery", parameters.searchQuery);
    query.set("searchOffset", pageOffset(parameters.searchOffset));
    query.set("searchScope", parameters.searchScope);
    const entry = searchEntry(parameters.searchEntry);
    if (entry) query.set("searchEntry", entry);
  }
  return query.size ? "?" + query : "";
}
