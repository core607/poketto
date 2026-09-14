package io.github.core607.poketto.executor.internal;

/** Stable storage outcomes; responses must not include private filesystem paths or retained content. */
final class RetainedCopyException extends RuntimeException {
    enum Reason {
        MISSING,
        STALE,
        EXPIRED,
        BUSY,
        LIMIT,
        UNAVAILABLE,
        UNCERTAIN
    }

    private final Reason reason;

    RetainedCopyException(Reason reason) {
        super("retained copy storage: " + reason);
        this.reason = reason;
    }

    RetainedCopyException(Reason reason, Throwable cause) {
        super("retained copy storage: " + reason, cause);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
