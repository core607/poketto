package io.github.core607.poketto.content;

import java.time.Instant;
import java.util.List;

/** Publication-approved fields; repositoryPath is for asset resolution, not a public response field. */
public record PublicArticle(
        String repositoryPath,
        String route,
        String title,
        String body,
        List<String> tags,
        Instant createdAt,
        Instant updatedAt,
        boolean folderPage) {
    public PublicArticle {
        tags = List.copyOf(tags);
    }
}
