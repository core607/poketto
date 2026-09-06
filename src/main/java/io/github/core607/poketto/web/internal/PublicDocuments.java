package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import java.time.Instant;
import java.util.List;

/** Public scope is fixed here, before route lookup, filtering, snippets, or tag enumeration. */
final class PublicDocuments {
    private final PublicContentSnapshots snapshots;
    private final WorkspaceCatalog workspaces;
    private final AssetService assets;

    PublicDocuments(PublicContentSnapshots snapshots, WorkspaceCatalog workspaces, AssetService assets) {
        this.snapshots = snapshots;
        this.workspaces = workspaces;
        this.assets = assets;
    }

    PublicContentSnapshot snapshot() {
        return snapshots.current(workspaces.defaultWorkspace().id());
    }

    Page search(String query, String tag, Instant from, Instant to, int offset, int limit) {
        if (query.length() > 200
                || tag.length() > 64
                || offset < 0
                || offset > 10_000
                || limit < 1
                || limit > 100
                || (from != null && to != null && from.isAfter(to)))
            throw new IllegalArgumentException("search exceeds its bounds or has an invalid date range");
        PublicContentSnapshot snapshot = snapshot();
        List<PublicArticle> matches = snapshot.articles().stream()
                .filter(article -> query.isEmpty()
                        || article.title().contains(query)
                        || article.body().contains(query))
                .filter(article -> tag.isEmpty() || article.tags().contains(tag))
                .filter(article -> from == null || !article.createdAt().isBefore(from))
                .filter(article -> to == null || !article.createdAt().isAfter(to))
                .toList();
        List<PublicDocumentSummary> items = matches.stream()
                .skip(offset)
                .limit(limit)
                .map(article -> PublicDocumentSummary.of(article, snippet(article.body(), query)))
                .toList();
        return new Page(
                snapshot.commit().orElse(null),
                snapshot.verifiedAt(),
                snapshot.expiresAt(),
                items,
                matches.size(),
                offset,
                limit);
    }

    PublicDocument find(String route) {
        if (route.length() > 256 || !route.startsWith("/")) throw notFound();
        return assets.publicDocument(workspaces.defaultWorkspace().id(), route)
                .map(value -> PublicDocument.of(value.article(), value.snapshot(), value.media()))
                .orElseThrow(PublicDocuments::notFound);
    }

    Tags tags(int offset, int limit) {
        if (offset < 0 || offset > 320_000 || limit < 1 || limit > 200)
            throw new IllegalArgumentException("tag page exceeds its bounds");
        PublicContentSnapshot snapshot = snapshot();
        List<String> tags = snapshot.articles().stream()
                .flatMap(article -> article.tags().stream())
                .distinct()
                .sorted()
                .toList();
        return new Tags(
                snapshot.commit().orElse(null),
                snapshot.verifiedAt(),
                snapshot.expiresAt(),
                tags.stream().skip(offset).limit(limit).toList(),
                tags.size(),
                offset,
                limit);
    }

    private static String snippet(String body, String query) {
        int match = query.isEmpty() ? 0 : Math.max(0, body.indexOf(query));
        int start = Math.max(0, match - 60);
        int end = Math.min(body.length(), start + 240);
        if (start > 0 && Character.isLowSurrogate(body.charAt(start))) start--;
        if (end < body.length() && end > 0 && Character.isHighSurrogate(body.charAt(end - 1))) end--;
        return body.substring(start, end);
    }

    private static PublicResourceNotFoundException notFound() {
        return new PublicResourceNotFoundException("public document not found");
    }

    record Page(
            String commit,
            Instant verifiedAt,
            Instant expiresAt,
            List<PublicDocumentSummary> items,
            int total,
            int offset,
            int limit) {}

    record Tags(
            String commit,
            Instant verifiedAt,
            Instant expiresAt,
            List<String> tags,
            int total,
            int offset,
            int limit) {}
}
