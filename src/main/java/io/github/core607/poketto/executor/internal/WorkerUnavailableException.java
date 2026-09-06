package io.github.core607.poketto.executor.internal;

/** No worker success or process-tree termination is inferred from a failed exchange. */
final class WorkerUnavailableException extends IllegalStateException {
    WorkerUnavailableException() {
        super("Isolated worker unavailable; execution was not confirmed. Start a new MCP session before retrying.");
    }
}
