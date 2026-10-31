package io.github.core607.poketto.executor.internal;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Recover abandoned ephemeral game jobs without retaining one application journal per request. */
final class GameSandboxMaintenance implements AutoCloseable {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("poketto-game-cleanup").factory());

    GameSandboxMaintenance(IsolatedGameRunner runner) {
        scheduler.scheduleWithFixedDelay(runner::collectSafely, 0, 1, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Game cleanup collector did not stop");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping game cleanup", interrupted);
        }
    }
}
