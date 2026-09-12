package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.function.Function;

/**
 * The public site's view of a workspace. Readers take one verified, unexpired snapshot and
 * never fetch on their own, so a slow or failing remote cannot turn a page request into a
 * network call, and missing or invalid publication policy exposes nothing.
 */
public interface PublicContentSnapshots {
    void ensureReady(WorkspaceId workspaceId);

    PublicContentSnapshot refresh(WorkspaceId workspaceId);
    /** Never fetches. Throws ContentRepositoryException when absent, expired, or publication is invalid. */
    PublicContentSnapshot current(WorkspaceId workspaceId);

    /**
     * Runs a short callback atomically with installation and withdrawal of current publication,
     * without waiting for network fetches. Callbacks must not refresh or enter mutable repository
     * operations; exact immutable-object reads are permitted. Throws when publication is unavailable.
     */
    <T> T withCurrent(WorkspaceId workspaceId, Function<PublicContentSnapshot, T> action);
}
