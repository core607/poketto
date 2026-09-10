package io.github.core607.poketto.content;

/**
 * Reports that a remote ref update lost its response and the authoritative ref could not be
 * re-read. The caller must not retry the write blindly.
 */
public final class RepositoryWriteAmbiguousException extends RuntimeException {
    private final java.util.Optional<RepositoryWriteAttempt> attempt;

    public RepositoryWriteAmbiguousException(String message) {
        super(message);
        attempt = java.util.Optional.empty();
    }

    public RepositoryWriteAmbiguousException(String message, RepositoryWriteAttempt attempt) {
        super(message);
        this.attempt = java.util.Optional.of(attempt);
    }

    public java.util.Optional<RepositoryWriteAttempt> attempt() {
        return attempt;
    }
}
