package io.github.core607.poketto.content;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Current local-to-remote replication state for one workspace. */
public record GitReplicationStatus(
        Optional<String> localHead,
        Optional<String> lastMirroredCommit,
        int lagCommits,
        Optional<Duration> lagDuration,
        Optional<Instant> lastAttemptAt,
        Optional<GitReplicationFailure> failure) {

    public GitReplicationStatus {
        Objects.requireNonNull(localHead, "local head must not be null");
        Objects.requireNonNull(lastMirroredCommit, "last mirrored commit must not be null");
        Objects.requireNonNull(lagDuration, "lag duration must not be null");
        Objects.requireNonNull(lastAttemptAt, "last attempt must not be null");
        Objects.requireNonNull(failure, "failure must not be null");
        if (lagCommits < 0) {
            throw new IllegalArgumentException("replication lag commits must not be negative");
        }
    }
}
