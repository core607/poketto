package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.content.ContentRepositoryStore;
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
 * <p>Every read resolves current remote {@code main}; there is no cross-request cache, so a
 * successful publish is visible on the next request.
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
        WorkspaceId workspaceId = workspaces.defaultWorkspace().id();
        return store.scan(workspaceId).stream()
                .filter(document -> document.content().metadata().visibility() == DocumentVisibility.PUBLIC)
                .sorted(NEWEST_PUBLICATION_FIRST)
                .toList();
    }

    Optional<StoredDocument> find(DocumentId documentId) {
        Objects.requireNonNull(documentId, "document id must not be null");
        return list().stream()
                .filter(document -> document.content().metadata().id().equals(documentId))
                .findFirst();
    }
}
