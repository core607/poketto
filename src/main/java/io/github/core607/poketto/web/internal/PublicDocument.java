package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.content.DocumentMetadata;
import io.github.core607.poketto.content.StoredDocument;
import java.time.Instant;
import java.util.List;

/**
 * One public document with its uninterpreted Markdown body. Rendering to HTML, including
 * sanitization, happens in the presentation layer, not here.
 */
record PublicDocument(
        String id,
        String title,
        List<String> tags,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        String body) {

    static PublicDocument of(StoredDocument document) {
        DocumentMetadata metadata = document.content().metadata();
        return new PublicDocument(
                metadata.id().toString(),
                metadata.title(),
                metadata.tags(),
                metadata.createdAt(),
                metadata.updatedAt(),
                metadata.publishedAt().orElse(null),
                document.content().body());
    }
}
