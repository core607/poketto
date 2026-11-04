package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.micrometer.core.instrument.MeterRegistry;

/** Health, metrics and collection follow the backend that owns repository admission. */
interface RepositoryRuntime extends RepositoryExecutor {
    void checkWorker();

    void bindMetrics(MeterRegistry registry);

    int collectExpiredCopies();
}
