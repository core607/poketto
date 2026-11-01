package io.github.core607.poketto.executor.internal;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;

/** Counts local admission ownership, including reservations whose native containment remains unknown. */
final class NativeRepositoryMetrics {
    private final LongAdder created = new LongAdder();
    private final LongAdder released = new LongAdder();
    private final Map<String, LongAdder> refused = Map.of(
            "copy_mismatch", new LongAdder(),
            "copy_limit", new LongAdder(),
            "session_limit", new LongAdder(),
            "operation_limit", new LongAdder(),
            "account_limit", new LongAdder(),
            "session_busy", new LongAdder());

    void created() {
        created.increment();
    }

    void released() {
        released.increment();
    }

    void refused(String reason) {
        refused.get(reason).increment();
    }

    void bind(MeterRegistry registry, IntSupplier sessions, IntSupplier operations) {
        Gauge.builder("poketto.executor.sessions.active", sessions, IntSupplier::getAsInt)
                .strongReference(true)
                .register(registry);
        Gauge.builder("poketto.executor.operations.active", operations, IntSupplier::getAsInt)
                .strongReference(true)
                .register(registry);
        FunctionCounter.builder("poketto.executor.sessions.created", created, LongAdder::doubleValue)
                .register(registry);
        FunctionCounter.builder("poketto.executor.sessions.released", released, LongAdder::doubleValue)
                .register(registry);
        refused.forEach((reason, counter) -> FunctionCounter.builder(
                        "poketto.executor.admission.rejected", counter, LongAdder::doubleValue)
                .tag("reason", reason)
                .register(registry));
    }
}
