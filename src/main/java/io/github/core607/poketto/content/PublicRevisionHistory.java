package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Instant;
import java.util.List;

/**
 * Earlier public bodies of one served article, read from the immutable objects of the served
 * commit without fetching. Commit ids, messages and identities never leave this interface.
 */
public interface PublicRevisionHistory {
    /**
     * Walks first parents from {@code commit} while the article stays publishable at {@code path}
     * with the same {@code route}; stops at the first commit where it was not.
     */
    Revisions read(WorkspaceId workspace, String commit, String path, String route);

    /** A body and the commit time at which it first appeared in the walked stretch. */
    record Revision(Instant savedAt, String body) {}

    /**
     * Newest first. {@code complete} is false when a bound stopped the walk, or when it ended at an
     * earlier version that could not be read, so earlier public text may exist.
     */
    record Revisions(List<Revision> newestFirst, boolean complete) {
        public Revisions {
            newestFirst = List.copyOf(newestFirst);
        }
    }
}
