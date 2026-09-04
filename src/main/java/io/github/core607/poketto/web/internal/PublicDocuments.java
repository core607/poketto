package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.ContentRepositoryStore;
import io.github.core607.poketto.content.ContentSnapshot;
import io.github.core607.poketto.content.DocumentId;
import io.github.core607.poketto.content.DocumentVisibility;
import io.github.core607.poketto.content.StoredDocument;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Public-content-only read surface over the default workspace. The interface carries no visibility
 * parameter, so no entrance built on it can widen the scope to private documents.
 *
 * <p>Every read serves the workspace's current validated snapshot and never contacts the remote.
 * A write acknowledged by this service is visible on the next request; a direct owner push is
 * visible after the next successful background refresh.
 */
final class PublicDocuments {

    private static final Comparator<StoredDocument> NEWEST_PUBLICATION_FIRST = Comparator.comparing(
                    (StoredDocument document) -> document.content().metadata().publishedAt(),
                    Comparator.comparing((Optional<Instant> published) -> published.orElse(Instant.MIN))
                            .reversed())
            .thenComparing(document -> document.content().metadata().id().toString());

    private final ContentRepositoryStore store;
    private final WorkspaceCatalog workspaces;

    PublicDocuments(ContentRepositoryStore store, WorkspaceCatalog workspaces) {
        this.store = Objects.requireNonNull(store, "content repository store must not be null");
        this.workspaces = Objects.requireNonNull(workspaces, "workspace catalog must not be null");
    }

    List<StoredDocument> list() {
        return snapshot().documents().stream()
                .filter(PublicDocuments::isPublic)
                .sorted(NEWEST_PUBLICATION_FIRST)
                .toList();
    }

    Optional<StoredDocument> find(DocumentId documentId) {
        Objects.requireNonNull(documentId, "document id must not be null");
        return snapshot().find(documentId).filter(PublicDocuments::isPublic);
    }

    private ContentSnapshot snapshot() {
        WorkspaceId workspaceId = workspaces.defaultWorkspace().id();
        return store.snapshot(workspaceId)
                .orElseThrow(() ->
                        new ContentRepositoryException("workspace " + workspaceId + " has no validated snapshot"));
    }

    private static boolean isPublic(StoredDocument document) {
        return document.content().metadata().visibility() == DocumentVisibility.PUBLIC;
    }
}
