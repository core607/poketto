package io.github.core607.poketto.mcp.internal;

import io.github.core607.poketto.mcp.ExecutionCancellation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class McpCancellation implements ExecutionCancellation {
    static final String CONTEXT_KEY = "poketto.mcp.cancellation";
    private static final Logger log = LoggerFactory.getLogger(McpCancellation.class);
    private final Map<Object, Runnable> callbacks = new HashMap<>();
    private boolean cancelled;
    private boolean finished;

    @Override
    public synchronized boolean isCancelled() {
        return cancelled;
    }

    @Override
    public Registration onCancel(Runnable terminate) {
        Objects.requireNonNull(terminate);
        Object registration = new Object();
        synchronized (this) {
            if (!cancelled) {
                if (!finished) callbacks.put(registration, terminate);
                return () -> {
                    synchronized (this) {
                        callbacks.remove(registration);
                    }
                };
            }
        }
        invoke(terminate);
        return () -> {};
    }

    void cancel() {
        ArrayList<Runnable> pending;
        synchronized (this) {
            if (cancelled || finished) return;
            cancelled = true;
            pending = new ArrayList<>(callbacks.values());
            callbacks.clear();
        }
        pending.forEach(McpCancellation::invoke);
    }

    synchronized void finish() {
        finished = true;
        callbacks.clear();
    }

    private static void invoke(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException exception) {
            log.error("Execution cancellation callback failed; worker leases must still enforce expiry");
        }
    }
}
