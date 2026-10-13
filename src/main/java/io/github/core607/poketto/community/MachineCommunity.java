package io.github.core607.poketto.community;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.MachineAccounts;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.List;
import java.util.UUID;

/** Explicitly delegated machine participation; the ordinary browser community API stays browser-only. */
public interface MachineCommunity {
    UUID comment(
            AuthPrincipal actor,
            WorkspaceId connectionWorkspace,
            String reference,
            Community.CommentInput input,
            String clientName);

    void sign(AuthPrincipal actor, WorkspaceId connectionWorkspace, String signature);

    List<Paper> wall(MachineAccounts.Identity viewer);

    record Paper(UUID commentId, Community.ArticleCard article, String accountName, String excerpt) {}

    record Signature(String value) {
        public Signature {
            if (value == null) {
                throw new IllegalArgumentException("A signature is required; use empty text to clear it");
            }
            CommunityText.requireWellFormed(value, "signature");
            if (value.codePointCount(0, value.length()) > 80) {
                throw new IllegalArgumentException("A signature allows 80 characters");
            }
            if (value.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("A signature cannot contain control characters");
            }
            value = value.strip();
        }
    }
}
