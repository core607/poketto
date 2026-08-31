package io.github.core607.poketto.content;

import java.util.Objects;

/**
 * Reports that no document with the requested id exists on the workspace repository's {@code main}.
 * A retry after a lost delete acknowledgement reads as already applied.
 */
public final class DocumentNotFoundException extends RuntimeException {

    private final transient DocumentId documentId;

    public DocumentNotFoundException(String message, DocumentId documentId) {
        super(message);
        this.documentId = Objects.requireNonNull(documentId, "document id must not be null");
    }

    public DocumentId documentId() {
        return documentId;
    }
}
