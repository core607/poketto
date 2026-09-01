package io.github.core607.poketto.content;

/**
 * Reports that a remote ref update lost its response and the authoritative ref could not be
 * re-read. The caller must not retry the write blindly.
 */
public final class RepositoryWriteAmbiguousException extends RuntimeException {

    public RepositoryWriteAmbiguousException(String message) {
        super(message);
    }
}
