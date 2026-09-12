package io.github.core607.poketto.content;

import java.util.Optional;

/**
 * Reports that a remote ref update lost its response and the authoritative ref could not be
 * re-read. The caller must not retry the write blindly.
 */
public final class RepositoryWriteAmbiguousException extends RuntimeException {
    private final Optional<RepositoryWriteAttempt> attempt;

    public RepositoryWriteAmbiguousException(String message) {
        super(message);
        attempt = Optional.empty();
    }

    public RepositoryWriteAmbiguousException(String message, RepositoryWriteAttempt attempt) {
        super(message);
        this.attempt = Optional.of(attempt);
    }

    public Optional<RepositoryWriteAttempt> attempt() {
        return attempt;
    }
}
