package io.github.core607.poketto.content;

/**
 * Reports an invalid or unavailable workspace content repository.
 */
public final class ContentRepositoryException extends RuntimeException {

    public ContentRepositoryException(String message) {
        super(message);
    }

    public ContentRepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
