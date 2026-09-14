package io.github.core607.poketto.content;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;

/**
 * Moves Git and indexed paths atomically, repairing Markdown references without fetching originals.
 * Requires full read and write authority, plus publish authority for affected public paths. The
 * result's revisions cover rewritten text and deleted Git paths; object-only moves need a fresh read
 * before a later text edit. Repository conflicts and uncertain acknowledgements follow patch semantics.
 */
public interface RepositoryMoveService {
    /** Prepares bounded local preconditions from current authority; does not mutate the repository. */
    RepositoryMovePlan plan(AuthPrincipal principal, WorkspaceId workspace, RepositoryMoveRequest request);

    RepositoryPatchResult move(AuthPrincipal principal, WorkspaceId workspace, RepositoryMoveRequest request);

    /** Requires the host checkpoint to complete before any remote pointer advance. */
    default RepositoryPatchResult move(
            AuthPrincipal principal,
            WorkspaceId workspace,
            RepositoryMoveRequest request,
            RepositoryWriteCheckpoint checkpoint) {
        throw new UnsupportedOperationException("checkpointed repository moves are unavailable");
    }

    /** Reconciles or retries the identical host-retained move commit under current authorization. */
    RepositoryPatchResult recover(
            AuthPrincipal principal,
            WorkspaceId workspace,
            RepositoryMoveRequest request,
            RepositoryWriteAttempt attempt);

    /** Reconciliation is read-only when already committed; a retry passes through the checkpoint. */
    default RepositoryPatchResult recover(
            AuthPrincipal principal,
            WorkspaceId workspace,
            RepositoryMoveRequest request,
            RepositoryWriteAttempt attempt,
            RepositoryWriteCheckpoint checkpoint) {
        throw new UnsupportedOperationException("checkpointed repository move recovery is unavailable");
    }
}
