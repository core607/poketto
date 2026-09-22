package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Publication-approved snapshot. Grants derived from it must expire no later than expiresAt. */
public record PublicContentSnapshot(
        WorkspaceId workspaceId,
        Optional<String> commit,
        Instant verifiedAt,
        Instant expiresAt,
        List<PublicArticle> articles,
        PublicCollections collections) {
    public PublicContentSnapshot(
            WorkspaceId workspaceId,
            Optional<String> commit,
            Instant verifiedAt,
            Instant expiresAt,
            List<PublicArticle> articles) {
        this(workspaceId, commit, verifiedAt, expiresAt, articles, new PublicCollections(articles));
    }

    public PublicContentSnapshot {
        Set<UUID> seen = new HashSet<>();
        Set<UUID> duplicates = new HashSet<>();
        for (PublicArticle article : articles) {
            if (article.articleId() != null && !seen.add(article.articleId())) {
                duplicates.add(article.articleId());
            }
        }
        articles = articles.stream()
                .map(article -> duplicates.contains(article.articleId()) ? article.withoutIdentity() : article)
                .toList();
    }
}
