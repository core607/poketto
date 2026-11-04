package io.github.core607.poketto.executor.internal;

import java.util.Optional;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Checks the execution protocol without creating a lease or touching an account's files. */
// Health groups and contributors share a namespace; the public group is executor.
@Component("executorProtocolHealthIndicator")
final class ExecutorHealthIndicator implements HealthIndicator {
    private final Optional<RepositoryRuntime> executor;

    ExecutorHealthIndicator(Optional<RepositoryRuntime> executor) {
        this.executor = executor;
    }

    @Override
    public Health health() {
        try {
            executor.ifPresent(RepositoryRuntime::checkWorker);
            return Health.up().build();
        } catch (WorkerUnavailableException | SandcrabClient.Rejected unavailable) {
            // The health response never includes the endpoint, host paths or native error payload.
            return Health.down().build();
        }
    }
}
