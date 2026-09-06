package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

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
