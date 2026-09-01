package io.github.core607.poketto.content;

import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of one document write.
 *
 * <p>{@code commitId} names the commit that holds the reported state, and {@code committed}
 * reports whether this operation produced it. An operation that finds the repository already in
 * the requested state succeeds with {@code committed} false and the unchanged commit.
 *
 * <p>{@code mirrored} reports whether the named commit has been verified on the configured remote
 * and recorded in the local replication checkpoint. {@code revision} is absent only for a delete,
 * because no blob remains to hash. Projection observations belong to the projection contract, not
 * to this result.
 */
public record DocumentWriteResult(
        DocumentId documentId,
        String commitId,
        boolean committed,
        boolean mirrored,
        String repositoryPath,
        Optional<DocumentRevision> revision) {

    public DocumentWriteResult {
        Objects.requireNonNull(documentId, "document id must not be null");
        Objects.requireNonNull(commitId, "commit id must not be null");
        Objects.requireNonNull(repositoryPath, "document repository path must not be null");
        Objects.requireNonNull(revision, "document revision must not be null");
        if (commitId.isBlank()) {
            throw new IllegalArgumentException("commit id must not be blank");
        }
    }
}
