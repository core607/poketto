package io.github.core607.poketto.executor.internal;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Metadata collection has its own thread so scans cannot delay worker lease renewal. */
final class RetainedCopyMaintenance implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RetainedCopyMaintenance.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("poketto-retained-cleanup").factory());

    RetainedCopyMaintenance(RetainedCopyStore store, Duration interval) {
        ProtocolValues.require(interval.toMillis() > 0, "collection interval", "must be positive");
        scheduler.scheduleWithFixedDelay(() -> collect(store), 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void collect(RetainedCopyStore store) {
        try {
            store.collectExpired();
        } catch (RetainedCopyException failure) {
            if (failure.reason() == RetainedCopyException.Reason.BUSY) {
                log.debug("Retained metadata collection deferred while metadata is busy");
            } else {
                log.warn("Retained metadata collection failed; records remain for a later attempt", failure);
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Retained metadata collector did not stop");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping retained metadata collection", failure);
        }
    }
}
