package io.github.core607.poketto.content;

/**
 * Synchronous host persistence barrier before advancing remote Git. The caller retains the authorized
 * patch or move and its baseline before entering the write service. This barrier adds the exact
 * candidate commit bytes to that retained intent; returning permits the remote write. Throwing must
 * prevent it. A no-op write or reconciliation of an already committed attempt does not invoke it.
 */
@FunctionalInterface
public interface RepositoryWriteCheckpoint {
    /** For callers without a retained-work contract; this barrier provides no durability. */
    RepositoryWriteCheckpoint UNTRACKED = attempt -> {};

    void retain(RepositoryWriteAttempt attempt);
}
