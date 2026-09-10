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
    RepositoryPatchResult move(AuthPrincipal principal, WorkspaceId workspace, RepositoryMoveRequest request);

    /** Reconciles or retries the identical host-retained move commit under current authorization. */
    RepositoryPatchResult recover(
            AuthPrincipal principal,
            WorkspaceId workspace,
            RepositoryMoveRequest request,
            RepositoryWriteAttempt attempt);
}
