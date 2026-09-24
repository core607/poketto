package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.PublicRevisionHistory;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Revision history of one served article, shown only where the space owner turned it on. */
final class PublicHistory {
    private final WorkspacePublications publications;
    private final PublicContentSnapshots snapshots;
    private final PublicRevisionHistory history;
    private final Semaphore readers = new Semaphore(2);

    PublicHistory(WorkspacePublications publications, PublicContentSnapshots snapshots, PublicRevisionHistory history) {
        this.publications = publications;
        this.snapshots = snapshots;
        this.history = history;
    }

    History read(String slug, String route) {
        var workspace = shown(slug).workspaceId();
        if (route.length() > 256 || !route.startsWith("/")) {
            throw notFound();
        }
        if (!readers.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "public-history-busy");
        }
        try {
            PublicContentSnapshot snapshot = snapshots.current(workspace);
            PublicArticle article = served(snapshot, route).orElseThrow(PublicHistory::notFound);
            var revisions = history.read(workspace, snapshot.commit().orElseThrow(), article.repositoryPath(), route);
            // The walk ran outside publication, so it stands only if the same commit still serves the article.
            boolean unchanged = snapshots.withCurrent(
                    workspace,
                    current -> current.commit().equals(snapshot.commit())
                            && served(current, route).isPresent());
            shown(slug);
            if (!unchanged) {
                throw new ContentRepositoryException("public snapshot changed while history was read");
            }
            List<Version> versions = revisions.newestFirst().reversed().stream()
                    .map(revision -> new Version(revision.savedAt(), revision.body()))
                    .toList();
            return new History(route, article.title(), versions, revisions.complete());
        } finally {
            readers.release();
        }
    }

    private WorkspacePublications.Publication shown(String slug) {
        return publications
                .findPublished(slug)
                .filter(WorkspacePublications.Publication::publicHistory)
                .orElseThrow(PublicHistory::notFound);
    }

    private static Optional<PublicArticle> served(PublicContentSnapshot snapshot, String route) {
        return snapshot.articles().stream()
                .filter(article -> article.route().equals(route))
                .findFirst();
    }

    private static PublicResourceNotFoundException notFound() {
        return new PublicResourceNotFoundException("public revision history not found");
    }

    /** Oldest first; {@code complete} is false when earlier public versions may exist but are not listed. */
    record History(String route, String title, List<Version> versions, boolean complete) {}

    record Version(Instant savedAt, String body) {}
}
