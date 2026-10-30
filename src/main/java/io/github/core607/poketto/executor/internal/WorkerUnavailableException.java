package io.github.core607.poketto.executor.internal;

/**
 * No worker success or process-tree termination is inferred from a failed exchange.
 *
 * <p>The message is returned to the caller, so it states what to do rather than what broke. The
 * cause carries the real failure for the log and must not be added to the message.
 */
final class WorkerUnavailableException extends IllegalStateException {
    private static final String ADVICE =
            "Isolated worker unavailable; execution was not confirmed. Inspect the working copy before retrying writes.";
    private final boolean mayHaveExecuted;

    WorkerUnavailableException() {
        super(ADVICE);
        mayHaveExecuted = true;
    }

    WorkerUnavailableException(Throwable cause) {
        this(cause, true);
    }

    WorkerUnavailableException(Throwable cause, boolean mayHaveExecuted) {
        super(ADVICE, cause);
        this.mayHaveExecuted = mayHaveExecuted;
    }

    boolean mayHaveExecuted() {
        return mayHaveExecuted;
    }
}
