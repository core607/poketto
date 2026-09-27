package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.eclipse.jgit.lib.ObjectReader;

/**
 * Resolves workspace identity to an authoritative ref without exposing provider coordinates or
 * credentials to content callers.
 */
interface RepositoryAuthority {

    /** Prepares remote credentials before the action acquires relational authorization locks. */
    default <T> T withPreparedCredentials(WorkspaceId workspace, Supplier<T> action) {
        return action.get();
    }

    /**
     * Fetches remote {@code main} under the workspace lock and records it as the cache's local
     * {@code main}. Files are never checked out, so nothing in the cache's worktree is content.
     */
    <T> T readObjects(WorkspaceId workspaceId, SnapshotReader<T> reader);

    /**
     * Reads the cache as it stands without contacting the remote. The snapshot commit is the last
     * locally recorded main, which may lag or differ from remote main.
     */
    <T> T readCache(WorkspaceId workspaceId, SnapshotReader<T> reader);

    /**
     * Reads explicit immutable object ids without fetching or acquiring the workspace mutex,
     * including when another operation is fetching. Cache opening uses only the short lifecycle
     * lock. The cache remains in use until its reader and repository have closed. The
     * callback is synchronous, must not close the supplied reader, and must not return or retain
     * readers, streams or RevWalk objects.
     */
    <T> T readImmutableObjects(WorkspaceId workspaceId, ObjectReaderAction<T> action);

    /**
     * After successful source validation, retains this cache until a future expiry at most five
     * minutes away. The reader pin remains held until protection is installed and handles close.
     */
    void protectImmutableObjects(WorkspaceId workspaceId, Instant expiresAt, ObjectReaderAction<Void> validation);

    /** Writes Git objects and advances the exact remote ref without checking out repository files. */
    <T> T writeObjects(WorkspaceId workspaceId, CandidateWriter<T> writer);

    @FunctionalInterface
    interface SnapshotReader<T> {

        T read(Snapshot snapshot);
    }

    @FunctionalInterface
    interface ObjectReaderAction<T> {
        T read(ObjectReader objects) throws IOException;
    }

    @FunctionalInterface
    interface CandidateWriter<T> {

        T write(Snapshot snapshot, RefAdvancer advancer);
    }

    @FunctionalInterface
    interface RefAdvancer {

        void advance(String candidateCommit);
    }

    record Snapshot(Path worktree, Optional<String> commitId) {

        public Snapshot {
            Objects.requireNonNull(worktree, "snapshot worktree must not be null");
            Objects.requireNonNull(commitId, "snapshot commit must not be null");
            worktree = worktree.toAbsolutePath().normalize();
        }
    }
}
