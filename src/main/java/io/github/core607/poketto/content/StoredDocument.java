package io.github.core607.poketto.content;

import java.util.Objects;

/**
 * One validated document from a committed repository tree.
 */
public record StoredDocument(
        String repositoryPath, DocumentContent content, DocumentRevision revision) {

    public StoredDocument {
        Objects.requireNonNull(repositoryPath, "document repository path must not be null");
        Objects.requireNonNull(content, "document content must not be null");
        Objects.requireNonNull(revision, "document revision must not be null");
    }
}
