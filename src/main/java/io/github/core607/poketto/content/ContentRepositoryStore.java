package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.List;

/**
 * Reads committed content through the workspace's remote repository authority. Local worktrees
 * are disposable caches and never become an acknowledgement boundary.
 */
public interface ContentRepositoryStore {

    /**
     * Resolves authoritative remote {@code main} and materializes its disposable local cache.
     */
    void ensureReady(WorkspaceId workspaceId);

    /**
     * Reads managed documents from one resolved remote {@code main} snapshot. Local cache changes
     * are discarded and never visible as repository content.
     */
    List<StoredDocument> scan(WorkspaceId workspaceId);
}
