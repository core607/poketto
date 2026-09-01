package io.github.core607.poketto.content;

/** Stable, credential-free failure categories exposed by Git replication status. */
public enum GitReplicationFailure {
    MISSING_REMOTE,
    AUTHENTICATION,
    PERMISSION_DENIED,
    REMOTE_REPOSITORY_MISSING,
    NON_FAST_FORWARD,
    NETWORK,
    TIMEOUT,
    DIVERGED,
    UNKNOWN
}
