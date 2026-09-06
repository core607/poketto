package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.content.PublicArticle;
import java.time.Instant;
import java.util.List;

record PublicDocumentSummary(
        String route, String title, List<String> tags, Instant createdAt, Instant updatedAt, String snippet) {
    static PublicDocumentSummary of(PublicArticle article, String snippet) {
        return new PublicDocumentSummary(
                article.route(), article.title(), article.tags(), article.createdAt(), article.updatedAt(), snippet);
    }
}
