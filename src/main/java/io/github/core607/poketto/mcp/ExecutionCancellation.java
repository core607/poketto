package io.github.core607.poketto.mcp;

/** A server-owned cancellation signal. Workers register process-tree termination before starting work. */
public interface ExecutionCancellation {
    boolean isCancelled();

    /** Runs immediately when cancellation already happened. Closing removes an unused callback. */
    Registration onCancel(Runnable terminate);

    interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
