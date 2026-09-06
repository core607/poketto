package io.github.core607.poketto.content;

import java.time.Instant;
import java.util.List;

/** Structured metadata never replaces the original source stored in {@code file}. */
public record RepositoryDocument(
        RepositoryFile file,
        String title,
        String body,
        List<String> tags,
        Instant createdAt,
        Instant updatedAt,
        String route,
        boolean folderPage,
        boolean privatePath) {
    public RepositoryDocument {
        tags = List.copyOf(tags);
    }
}
