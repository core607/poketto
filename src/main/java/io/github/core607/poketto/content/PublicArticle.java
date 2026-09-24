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
        UUID articleId,
        boolean featured,
        /** Not a public response field; null when the article is not scheduled. */
        Instant publishAt) {
    public PublicArticle {
        tags = List.copyOf(tags);
    }

    public PublicArticle(
            String repositoryPath,
            String route,
            String title,
            String body,
            List<String> tags,
            Instant createdAt,
            Instant updatedAt,
            boolean folderPage,
            String publicAuthor,
            UUID articleId,
            boolean featured) {
        this(
                repositoryPath,
                route,
                title,
                body,
                tags,
                createdAt,
                updatedAt,
                folderPage,
                publicAuthor,
                articleId,
                featured,
                null);
    }

    /** True while the article must stay off every public surface. */
    public boolean scheduledAfter(Instant now) {
        return publishAt != null && publishAt.isAfter(now);
    }

    /** An ambiguous identity cannot be used for interaction lookup; the article remains readable. */
    PublicArticle withoutIdentity() {
        return new PublicArticle(
                repositoryPath,
                route,
                title,
                body,
                tags,
                createdAt,
                updatedAt,
                folderPage,
                publicAuthor,
                null,
                featured,
                publishAt);
    }
}
