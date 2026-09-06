package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;

public interface PublicContentSnapshots {
    void ensureReady(WorkspaceId workspaceId);

    PublicContentSnapshot refresh(WorkspaceId workspaceId);
    /** Never fetches. Throws ContentRepositoryException when absent, expired, or publication is invalid. */
    PublicContentSnapshot current(WorkspaceId workspaceId);

    /** Executes against current publication while holding the same workspace lock as installation. Never fetches. */
    <T> T withCurrent(WorkspaceId workspaceId, java.util.function.Function<PublicContentSnapshot, T> action);
}
