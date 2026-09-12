package io.github.core607.poketto.executor.internal;

/**
 * No worker success or process-tree termination is inferred from a failed exchange.
 *
 * <p>The message is returned to the caller, so it states what to do rather than what broke. The
 * cause carries the real failure for the log and must not be added to the message.
 */
final class WorkerUnavailableException extends IllegalStateException {
    private static final String ADVICE =
            "Isolated worker unavailable; execution was not confirmed. Start a new MCP session before retrying.";

    WorkerUnavailableException() {
        super(ADVICE);
    }

    WorkerUnavailableException(Throwable cause) {
        super(ADVICE, cause);
    }
}
