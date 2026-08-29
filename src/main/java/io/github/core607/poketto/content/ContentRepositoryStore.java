package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.List;

/**
 * Owns the git-backed content worktree for one workspace at a time.
 */
public interface ContentRepositoryStore {

    /**
     * Creates an unborn {@code main} repository when the workspace directory is absent or empty,
     * or validates an existing repository without changing it.
     */
    void ensureReady(WorkspaceId workspaceId);

    /**
     * Reads managed documents from the committed {@code main} tree. Working-tree changes are not
     * visible until committed.
     */
    List<StoredDocument> scan(WorkspaceId workspaceId);
}
