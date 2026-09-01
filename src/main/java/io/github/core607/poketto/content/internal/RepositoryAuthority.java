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

    <T> T read(WorkspaceId workspaceId, SnapshotReader<T> reader);

    <T> T write(WorkspaceId workspaceId, CandidateWriter<T> writer);

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
