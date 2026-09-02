package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.content.DocumentMetadata;
import io.github.core607.poketto.content.StoredDocument;
import java.time.Instant;
import java.util.List;

/**
 * List entry for a public document. {@code publishedAt} is null only for a document that an owner
 * made public through a direct push without recording a publication time.
 */
record PublicDocumentSummary(String id, String title, List<String> tags, Instant publishedAt, Instant updatedAt) {

    static PublicDocumentSummary of(StoredDocument document) {
        DocumentMetadata metadata = document.content().metadata();
        return new PublicDocumentSummary(
                metadata.id().toString(),
                metadata.title(),
                metadata.tags(),
                metadata.publishedAt().orElse(null),
                metadata.updatedAt());
    }
}
