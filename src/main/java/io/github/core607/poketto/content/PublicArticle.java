package io.github.core607.poketto.content;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Publication-approved fields; repositoryPath is for asset resolution, not a public response field. */
public record PublicArticle(
        String repositoryPath,
        String route,
        String title,
        String body,
        List<String> tags,
        Instant createdAt,
        Instant updatedAt,
        boolean folderPage,
        String publicAuthor,
        UUID articleId) {
    public PublicArticle {
        tags = List.copyOf(tags);
    }

    /** An ambiguous identity cannot be used for interaction lookup; the article remains readable. */
    PublicArticle withoutIdentity() {
        return new PublicArticle(
                repositoryPath, route, title, body, tags, createdAt, updatedAt, folderPage, publicAuthor, null);
    }
}
