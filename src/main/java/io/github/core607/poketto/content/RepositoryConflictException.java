package io.github.core607.poketto.content;

/**
 * Reports that remote {@code main} changed after a machine write resolved its base commit.
 * Nothing was acknowledged, so the caller must re-read before deciding whether to try again.
 */
public final class RepositoryConflictException extends RuntimeException {

    public RepositoryConflictException(String message) {
        super(message);
    }
}
