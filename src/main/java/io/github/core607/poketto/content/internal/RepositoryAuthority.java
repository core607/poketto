package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.jgit.lib.ObjectReader;

/**
 * Resolves workspace identity to an authoritative ref without exposing provider coordinates or
 * credentials to content callers.
 */
interface RepositoryAuthority {

    void ensureReady(WorkspaceId workspaceId);

    /** Resolves current remote {@code main}, materializes it in the cache, and reads it. */
    <T> T read(WorkspaceId workspaceId, SnapshotReader<T> reader);

    /** Fetches remote main under the workspace lock without checking files out or validating a content format. */
    <T> T readObjects(WorkspaceId workspaceId, SnapshotReader<T> reader);

    /**
     * Reads the cache as it stands without contacting the remote. The snapshot commit is the last
     * locally recorded main, which may lag or differ from remote main and need not be checked out.
     */
    <T> T readCache(WorkspaceId workspaceId, SnapshotReader<T> reader);

    /**
     * Reads explicit immutable object ids without fetching or holding the workspace mutex during
     * the callback. The cache remains in use until its reader and repository have closed. The
     * callback is synchronous, must not close the supplied reader, and must not return or retain
     * readers, streams or RevWalk objects.
     */
    <T> T readImmutableObjects(WorkspaceId workspaceId, ObjectReaderAction<T> action);

    <T> T write(WorkspaceId workspaceId, CandidateWriter<T> writer);

    /** Writes Git objects and advances the exact remote ref without checking out repository files. */
    default <T> T writeObjects(WorkspaceId workspaceId, CandidateWriter<T> writer) {
        throw new UnsupportedOperationException("object-only repository writes are unavailable");
    }

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
