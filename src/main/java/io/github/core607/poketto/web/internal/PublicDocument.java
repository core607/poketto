package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import java.time.Instant;
import java.util.List;

/** Public Markdown body only; private frontmatter and raw source never cross this mapper. */
record PublicDocument(
        String commit,
        Instant verifiedAt,
        Instant expiresAt,
        String route,
        String title,
        List<String> tags,
        Instant createdAt,
        Instant updatedAt,
        boolean folderPage,
        String body) {
    static PublicDocument of(PublicArticle article, PublicContentSnapshot snapshot) {
        return new PublicDocument(
                snapshot.commit().orElse(null),
                snapshot.verifiedAt(),
                snapshot.expiresAt(),
                article.route(),
                article.title(),
                article.tags(),
                article.createdAt(),
                article.updatedAt(),
                article.folderPage(),
                article.body());
    }
}
