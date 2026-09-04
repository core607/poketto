package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.List;
import java.util.Optional;

/**
 * Reads committed content through the workspace's remote repository authority. Local worktrees
 * are disposable caches and never become an acknowledgement boundary.
 *
 * <p>Readers serve the workspace's current {@link ContentSnapshot}, which changes only when a
 * whole remote {@code main} commit passes validation: a refresh, or a write this service
 * acknowledged. A commit that fails validation never replaces the snapshot in service.
 */
public interface ContentRepositoryStore {

    /**
     * Establishes a validated snapshot for the workspace: from current remote {@code main}, or,
     * when that cannot be resolved or validated, from the last commit this store validated in
     * the workspace's cache.
     *
     * @throws ContentRepositoryException when neither source yields a valid snapshot
     */
    void ensureReady(WorkspaceId workspaceId);

    /**
     * Resolves current remote {@code main}, validates it, and makes it the current snapshot.
     *
     * @throws ContentRepositoryException when the remote cannot be resolved or the commit is
     *     invalid; the snapshot in service is unchanged
     */
    ContentSnapshot refresh(WorkspaceId workspaceId);

    /** The current validated snapshot, without contacting the remote. */
    Optional<ContentSnapshot> snapshot(WorkspaceId workspaceId);

    /**
     * Reads managed documents from one resolved remote {@code main} commit without changing the
     * snapshot in service. Local cache changes are discarded and never visible as content.
     */
    List<StoredDocument> scan(WorkspaceId workspaceId);
}
