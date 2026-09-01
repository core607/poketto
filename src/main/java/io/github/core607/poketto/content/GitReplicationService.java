package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;

/** Operational view of repository replication. */
public interface GitReplicationService {

    GitReplicationStatus status(WorkspaceId workspaceId);

    /** Retries a workspace after configuration, credentials, or remote state was repaired. */
    void retry(WorkspaceId workspaceId);
}
