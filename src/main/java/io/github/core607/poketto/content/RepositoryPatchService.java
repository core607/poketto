package io.github.core607.poketto.content;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;

/** Shared browser/MCP mutation boundary; authorization is revalidated for every operation. */
public interface RepositoryPatchService {
    RepositoryPatchResult apply(AuthPrincipal principal, WorkspaceId workspace, RepositoryPatch patch);

    /** Requires the host checkpoint to complete before any remote pointer advance. */
    default RepositoryPatchResult apply(
            AuthPrincipal principal,
            WorkspaceId workspace,
            RepositoryPatch patch,
            RepositoryWriteCheckpoint checkpoint) {
        throw new UnsupportedOperationException("checkpointed repository writes are unavailable");
    }

    /** Reconciles an exact host-retained attempt; retries only that commit if remote main still equals its base. */
    default RepositoryPatchResult recover(
            AuthPrincipal principal, WorkspaceId workspace, RepositoryPatch patch, RepositoryWriteAttempt attempt) {
        throw new UnsupportedOperationException("repository write recovery is unavailable");
    }

    /** Reconciliation is read-only when already committed; a retry passes through the checkpoint. */
    default RepositoryPatchResult recover(
            AuthPrincipal principal,
            WorkspaceId workspace,
            RepositoryPatch patch,
            RepositoryWriteAttempt attempt,
            RepositoryWriteCheckpoint checkpoint) {
        throw new UnsupportedOperationException("checkpointed repository recovery is unavailable");
    }
}
