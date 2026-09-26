package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Enumerates public metadata; each space is delivered from one currently approved snapshot. */
final class PublicSitemaps {
    private static final int MAX_SPACES = 10_000;
    private static final int MAX_ARTICLES = 49_999;
    private final WorkspacePublications publications;
    private final PublicContentSnapshots snapshots;

    PublicSitemaps(WorkspacePublications publications, PublicContentSnapshots snapshots) {
        this.publications = publications;
        this.snapshots = snapshots;
    }

    List<String> spaces() {
        var first = catalog();
        if (!first.equals(catalog())) {
            throw unavailable();
        }
        return first.stream().map(WorkspacePublications.Publication::slug).toList();
    }

    Space space(String slug) {
        var publication = published(slug);
        var result = snapshots.withCurrent(publication.workspaceId(), snapshot -> {
            if (snapshot.articles().size() > MAX_ARTICLES) {
                throw unavailable();
            }
            var pages = snapshot.articles().stream()
                    .map(article -> new Page(article.route(), article.updatedAt()))
                    .sorted(Comparator.comparing(Page::route))
                    .toList();
            return new Space(publication.slug(), pages);
        });
        if (!publication.equals(published(slug))) {
            throw unavailable();
        }
        return result;
    }

    private List<WorkspacePublications.Publication> catalog() {
        return PublishedSpaces.all(publications, MAX_SPACES, () -> {}, PublicSitemaps::unavailable);
    }

    private WorkspacePublications.Publication published(String slug) {
        return publications
                .findPublished(slug)
                .orElseThrow(() -> new PublicResourceNotFoundException("public space not found"));
    }

    private static ContentRepositoryException unavailable() {
        return new ContentRepositoryException("Public sitemap enumeration is unavailable");
    }

    record Page(String route, Instant updatedAt) {}

    record Space(String slug, List<Page> pages) {}
}
