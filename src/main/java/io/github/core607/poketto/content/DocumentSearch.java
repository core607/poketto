package io.github.core607.poketto.content;

import java.time.Instant;
import java.util.List;

/**
 * One document search, checked once when it is constructed. Authorized private search and the
 * public site ask the same question of different corpora, and the bounds, the matching rule and the
 * snippet are the same question in both: a query the private API accepts and the public site
 * refuses would be a difference in what a reader can find, visible only by trying both.
 */
public record DocumentSearch(String query, String tag, Instant from, Instant to, int offset, int limit) {

    // The query, tag, page and snippet bounds come from the repository authoring foundations
    // record; the offset ceiling comes from the blog browser interface record, which fixes the
    // same 10,000 for the article API and for the page that consumes it. Matching is a literal
    // scan over every document in the tree with no index behind it, so these are the numbers that
    // keep one request's work proportional to one page of results.
    public static final int MAX_QUERY_LENGTH = 200;
    public static final int MAX_TAG_LENGTH = 64;
    public static final int MAX_OFFSET = 10_000;
    public static final int MAX_LIMIT = 100;
    public static final int SNIPPET_LENGTH = 240;

    private static final int SNIPPET_LEAD = 60;

    public DocumentSearch {
        if (query == null
                || tag == null
                || query.length() > MAX_QUERY_LENGTH
                || tag.length() > MAX_TAG_LENGTH
                || offset < 0
                || offset > MAX_OFFSET
                || limit < 1
                || limit > MAX_LIMIT
                || (from != null && to != null && from.isAfter(to))) {
            throw new IllegalArgumentException("search exceeds its bounds or has an invalid date range");
        }
    }

    /**
     * Whether one document belongs in the result. An empty query or tag matches every document
     * rather than none: both arrive from a query string, where a parameter left out and a parameter
     * left blank mean the same thing, and reading either as "match nothing" would answer an
     * unfiltered browse with an empty page.
     */
    public boolean matches(String title, String body, List<String> tags, Instant createdAt) {
        return (query.isEmpty() || title.contains(query) || body.contains(query))
                && (tag.isEmpty() || tags.contains(tag))
                && (from == null || !createdAt.isBefore(from))
                && (to == null || !createdAt.isAfter(to));
    }

    /** The requested window of an already-filtered result; the caller still reports the full count. */
    public <T> List<T> page(List<T> matches) {
        return matches.stream().skip(offset).limit(limit).toList();
    }

    /**
     * The excerpt shown beside a hit, cut around the first occurrence of the query. Both edges are
     * pulled back off a surrogate pair, so the excerpt never ends in half of a character that the
     * reader would receive as a replacement glyph.
     */
    public String snippet(String body) {
        int match = query.isEmpty() ? 0 : Math.max(0, body.indexOf(query));
        int start = Math.max(0, match - SNIPPET_LEAD);
        int end = Math.min(body.length(), start + SNIPPET_LENGTH);
        if (start > 0 && Character.isLowSurrogate(body.charAt(start))) {
            start--;
        }
        if (end < body.length() && end > 0 && Character.isHighSurrogate(body.charAt(end - 1))) {
            end--;
        }
        return body.substring(start, end);
    }
}
