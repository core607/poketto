package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.content.DocumentSearch;
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
        var search = new DocumentSearch(query, tag, from, to, offset, limit);
        PublicContentSnapshot snapshot = snapshot();
        List<PublicArticle> matches = snapshot.articles().stream()
                .filter(article -> search.matches(article.title(), article.body(), article.tags(), article.createdAt()))
                .toList();
        List<PublicDocumentSummary> items = search.page(matches).stream()
                .map(article -> PublicDocumentSummary.of(article, search.snippet(article.body())))
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
        if (route.length() > 256 || !route.startsWith("/")) {
            throw notFound();
        }
        return assets.publicDocument(workspaces.defaultWorkspace().id(), route)
                .map(value -> PublicDocument.of(value.article(), value.snapshot(), value.media()))
                .orElseThrow(PublicDocuments::notFound);
    }

    Tags tags(int offset, int limit) {
        if (offset < 0 || offset > 320_000 || limit < 1 || limit > 200) {
            throw new IllegalArgumentException("tag page exceeds its bounds");
        }
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
