package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.time.Instant;

/** Presentation for an already-authorized file read; it never grants website delivery. */
final class PublicFilePresentation {
    private final WorkspacePublications publications;
    private final PublicContentSnapshots snapshots;

    PublicFilePresentation(WorkspacePublications publications, PublicContentSnapshots snapshots) {
        this.publications = publications;
        this.snapshots = snapshots;
    }

    Page page(RepositoryFile file) {
        if (file.expectedAbsence()) {
            return Page.state(State.UNSAVED);
        }
        if (!file.publicScope()) {
            return Page.state(State.PRIVATE);
        }
        WorkspacePublications.Publication publication = publications.settings(file.workspaceId());
        if (!publication.enabled()) {
            return Page.state(State.WEBSITE_DISABLED);
        }
        if (!publication.eligible()) {
            return Page.state(State.WEBSITE_RESTRICTED);
        }
        try {
            return snapshots.withCurrent(file.workspaceId(), snapshot -> {
                if (!snapshot.commit().equals(file.commit())) {
                    return Page.state(State.UNAVAILABLE);
                }
                Instant release = snapshot.scheduled().get(file.path());
                if (release != null) {
                    return new Page(State.SCHEDULED, publication.slug(), null, release);
                }
                return snapshot.articles().stream()
                        .filter(article -> article.repositoryPath().equals(file.path()))
                        .findFirst()
                        .map(article -> new Page(State.AVAILABLE, publication.slug(), article.route(), null))
                        .orElseGet(() -> Page.state(State.UNAVAILABLE));
            });
        } catch (ContentRepositoryException unavailable) {
            return Page.state(State.UNAVAILABLE);
        }
    }

    enum State {
        UNSAVED,
        PRIVATE,
        WEBSITE_DISABLED,
        WEBSITE_RESTRICTED,
        UNAVAILABLE,
        /** Publishable at this commit, but its {@code publish_at} is still ahead. */
        SCHEDULED,
        AVAILABLE
    }

    record Page(State state, String space, String route, Instant publishAt) {
        static Page state(State state) {
            return new Page(state, null, null, null);
        }
    }
}
