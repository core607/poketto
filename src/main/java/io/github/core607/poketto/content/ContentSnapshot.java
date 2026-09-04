package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Every managed document of one workspace at one remote {@code main} commit, validated as a
 * whole. A snapshot is immutable; a later commit produces a new snapshot or none at all.
 */
public final class ContentSnapshot {

    private final WorkspaceId workspaceId;
    private final Optional<String> commitId;
    private final List<StoredDocument> documents;
    private final Map<DocumentId, StoredDocument> byId;
    private final Instant validatedAt;

    public ContentSnapshot(
            WorkspaceId workspaceId, Optional<String> commitId, List<StoredDocument> documents, Instant validatedAt) {
        this.workspaceId = Objects.requireNonNull(workspaceId, "snapshot workspace must not be null");
        this.commitId = Objects.requireNonNull(commitId, "snapshot commit must not be null");
        this.documents = List.copyOf(Objects.requireNonNull(documents, "snapshot documents must not be null"));
        this.validatedAt = Objects.requireNonNull(validatedAt, "snapshot validation time must not be null");
        Map<DocumentId, StoredDocument> index = new LinkedHashMap<>();
        for (StoredDocument document : this.documents) {
            DocumentId id = document.content().metadata().id();
            if (index.putIfAbsent(id, document) != null) {
                throw new IllegalArgumentException("snapshot documents must have distinct ids: " + id);
            }
        }
        this.byId = Map.copyOf(index);
    }

    public WorkspaceId workspaceId() {
        return workspaceId;
    }

    /** Empty while remote {@code main} is unborn. */
    public Optional<String> commitId() {
        return commitId;
    }

    public List<StoredDocument> documents() {
        return documents;
    }

    public Optional<StoredDocument> find(DocumentId documentId) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(documentId, "document id must not be null")));
    }

    /** When the whole snapshot last passed validation against remote {@code main}. */
    public Instant validatedAt() {
        return validatedAt;
    }
}
