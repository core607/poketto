package io.github.core607.poketto.content;

import java.util.Objects;

/**
 * A replication failure whose message is safe to expose without remote credentials. The cause
 * keeps the underlying transport detail for server-side diagnosis and may name the remote, so
 * entrances expose the message and category, never the cause chain.
 */
public final class GitReplicationException extends RuntimeException {

    private final GitReplicationFailure failure;
    private final boolean transientFailure;

    public GitReplicationException(
            GitReplicationFailure failure, boolean transientFailure, String message) {
        super(message);
        this.failure = Objects.requireNonNull(failure, "replication failure must not be null");
        this.transientFailure = transientFailure;
    }

    public GitReplicationException(
            GitReplicationFailure failure,
            boolean transientFailure,
            String message,
            Throwable cause) {
        super(message, cause);
        this.failure = Objects.requireNonNull(failure, "replication failure must not be null");
        this.transientFailure = transientFailure;
    }

    public GitReplicationFailure failure() {
        return failure;
    }

    public boolean transientFailure() {
        return transientFailure;
    }
}
