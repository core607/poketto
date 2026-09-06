package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Publication-approved snapshot. Grants derived from it must expire no later than expiresAt. */
public record PublicContentSnapshot(
        WorkspaceId workspaceId,
        Optional<String> commit,
        Instant verifiedAt,
        Instant expiresAt,
        List<PublicArticle> articles) {
    public PublicContentSnapshot {
        articles = List.copyOf(articles);
    }
}
