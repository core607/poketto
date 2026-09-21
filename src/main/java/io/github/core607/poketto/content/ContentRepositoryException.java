package io.github.core607.poketto.content;

import java.util.Objects;

/**
 * Reports an invalid or unavailable workspace content repository. A recovery hint may be
 * exposed to authorized workspace callers; diagnostics and public responses remain separate.
 */
public final class ContentRepositoryException extends RuntimeException {
    public enum Recovery {
        NONE,
        RETRY,
        RECONNECT
    }

    private final Recovery recovery;

    public ContentRepositoryException(String message) {
        this(message, null);
    }

    public ContentRepositoryException(String message, Throwable cause) {
        this(message, Recovery.NONE, cause);
    }

    public ContentRepositoryException(String message, Recovery recovery, Throwable cause) {
        super(message, cause);
        this.recovery = Objects.requireNonNull(recovery, "repository recovery hint is required");
    }

    public Recovery recovery() {
        return recovery;
    }
}
