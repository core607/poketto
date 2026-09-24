package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Publication-approved snapshot. Grants derived from it must expire no later than expiresAt.
 * {@code articles} are public now; {@code scheduled} maps the repository paths of publishable articles
 * that are not yet due to their release instants, for authoring views only.
 */
public record PublicContentSnapshot(
        WorkspaceId workspaceId,
        Optional<String> commit,
        Instant verifiedAt,
        Instant expiresAt,
        List<PublicArticle> articles,
        PublicCollections collections,
        Map<String, Instant> scheduled) {
    public PublicContentSnapshot(
            WorkspaceId workspaceId,
            Optional<String> commit,
            Instant verifiedAt,
            Instant expiresAt,
            List<PublicArticle> articles,
            PublicCollections collections) {
        this(workspaceId, commit, verifiedAt, expiresAt, articles, collections, Map.of());
    }

    public PublicContentSnapshot(
            WorkspaceId workspaceId,
            Optional<String> commit,
            Instant verifiedAt,
            Instant expiresAt,
            List<PublicArticle> articles) {
        this(workspaceId, commit, verifiedAt, expiresAt, articles, new PublicCollections(articles));
    }

    public PublicContentSnapshot {
        scheduled = Map.copyOf(scheduled);
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
