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

    /** Requires the caller's owner-authorization transaction; see {@link SpaceProfiles#name}. */
    Publication setDisplayName(WorkspaceId workspace, String name);

    /** Requires the caller's owner-authorization transaction; see {@link SpaceProfiles#description}. */
    Publication setDescription(WorkspaceId workspace, String description);

    /** Requires the caller's owner-authorization transaction. */
    Publication setPublicHistory(WorkspaceId workspace, boolean shown);

    void requireEnabled(WorkspaceId workspace);

    record Publication(
            WorkspaceId workspaceId,
            String slug,
            String displayName,
            boolean enabled,
            boolean eligible,
            String publicAuthorName,
            String publicDescription,
            boolean publicHistory) {
        public Publication(
                WorkspaceId workspaceId,
                String slug,
                String displayName,
                boolean enabled,
                boolean eligible,
                String publicAuthorName) {
            this(workspaceId, slug, displayName, enabled, eligible, publicAuthorName, "", false);
        }

        public boolean publiclyEnabled() {
            return enabled && eligible;
        }

        public String authorName() {
            return PublicAuthorNames.select(publicAuthorName, displayName);
        }
    }
}
