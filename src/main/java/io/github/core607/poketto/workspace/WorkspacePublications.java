package io.github.core607.poketto.workspace;

import java.util.List;
import java.util.Optional;

/** Website delivery is independent of a member's access to repository-public files. */
public interface WorkspacePublications {
    Optional<Publication> findPublished(String slug);

    List<Publication> publishedAfter(Optional<WorkspaceId> after, int limit);

    Publication settings(WorkspaceId workspace);

    /** Requires the caller's owner-authorization transaction. */
    Publication setEnabled(WorkspaceId workspace, boolean enabled);

    void requireEnabled(WorkspaceId workspace);

    record Publication(WorkspaceId workspaceId, String slug, String displayName, boolean enabled) {}
}
