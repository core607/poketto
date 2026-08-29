package io.github.core607.poketto.content;

import java.util.Objects;

/**
 * Parsed document metadata and uninterpreted Markdown body.
 */
public record DocumentContent(DocumentMetadata metadata, String body) {

    public DocumentContent {
        Objects.requireNonNull(metadata, "document metadata must not be null");
        Objects.requireNonNull(body, "document body must not be null");
    }
}
