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

    /** Requires the caller's owner-authorization transaction. */
    Publication setAuthorName(WorkspaceId workspace, String name);

    void requireEnabled(WorkspaceId workspace);

    record Publication(
            WorkspaceId workspaceId, String slug, String displayName, boolean enabled, String publicAuthorName) {
        public String authorName() {
            return PublicAuthorNames.select(publicAuthorName, displayName);
        }
    }
}
