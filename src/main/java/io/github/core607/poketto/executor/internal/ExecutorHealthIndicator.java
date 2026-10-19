package io.github.core607.poketto.executor.internal;

import java.util.Optional;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Checks the execution protocol without creating a lease or touching an account's files. */
// Health groups and contributors share a namespace; the public group is executor.
@Component("executorProtocolHealthIndicator")
final class ExecutorHealthIndicator implements HealthIndicator {
    private final Optional<IsolatedRepositoryExecutor> executor;

    ExecutorHealthIndicator(Optional<IsolatedRepositoryExecutor> executor) {
        this.executor = executor;
    }

    @Override
    public Health health() {
        try {
            executor.ifPresent(IsolatedRepositoryExecutor::checkWorker);
            return Health.up().build();
        } catch (WorkerUnavailableException unavailable) {
            // WorkerClient logs the rejection; the health response never includes host paths or frames.
            return Health.down().build();
        }
    }
}
