package io.github.core607.poketto.content;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;

/** Shared browser/MCP mutation boundary; authorization is revalidated for every operation. */
public interface RepositoryPatchService {
    RepositoryPatchResult apply(AuthPrincipal principal, WorkspaceId workspace, RepositoryPatch patch);

    /** Reconciles an exact host-retained attempt; retries only that commit if remote main still equals its base. */
    default RepositoryPatchResult recover(
            AuthPrincipal principal, WorkspaceId workspace, RepositoryPatch patch, RepositoryWriteAttempt attempt) {
        throw new UnsupportedOperationException("repository write recovery is unavailable");
    }
}
