package io.github.core607.poketto.content;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Optional;

/**
 * Applies a body that someone else proposed and a reviewer accepted to one served article. The
 * article's frontmatter bytes stay as they are; only the text after it changes.
 */
public interface ReviewedBodyEdits {
    /**
     * Re-reads the article behind {@code route} at the remote head as {@code reviewer}, who needs
     * {@code PUBLISH}, and writes {@code body} when the current body still has {@code baseDigest}.
     * The commit carries {@code suggestedBy} as its {@code Poketto-Suggested-By} trailer. A remote
     * that moves during the write is re-read and retried a bounded number of times.
     */
    Outcome replaceBody(
            AuthPrincipal reviewer,
            WorkspaceId workspace,
            String route,
            String baseDigest,
            String body,
            WritePrincipal suggestedBy);

    enum Result {
        /** The body was written in {@link Outcome#commit()}. */
        APPLIED,
        /** The current body already equals the proposed one; nothing was written. */
        ALREADY_APPLIED,
        /** The article is no longer served, or its body no longer matches the base digest. */
        STALE
    }

    record Outcome(Result result, Optional<String> commit) {}
}
