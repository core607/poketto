package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.workspace.PublicAuthorNames;
import java.time.Instant;
import java.util.List;

record PublicDocumentSummary(
        String route,
        String title,
        List<String> tags,
        Instant createdAt,
        Instant updatedAt,
        String snippet,
        String authorName) {
    static PublicDocumentSummary of(PublicArticle article, String snippet, String workspaceAuthor) {
        return new PublicDocumentSummary(
                article.route(),
                article.title(),
                article.tags(),
                article.createdAt(),
                article.updatedAt(),
                snippet,
                PublicAuthorNames.select(article.publicAuthor(), workspaceAuthor));
    }
}
