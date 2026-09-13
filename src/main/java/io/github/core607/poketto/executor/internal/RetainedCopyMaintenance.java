package io.github.core607.poketto.executor.internal;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Each retained store has its own collector so scans cannot delay worker lease renewal or each other. */
final class RetainedCopyMaintenance implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RetainedCopyMaintenance.class);
    private final ScheduledExecutorService scheduler;

    RetainedCopyMaintenance(RetainedCopyStore store, Duration interval) {
        this(store::collectExpired, "metadata", interval);
    }

    RetainedCopyMaintenance(RetainedBaselineStore store, Duration interval) {
        this(store::collectUnused, "originals", interval);
    }

    private RetainedCopyMaintenance(Runnable action, String kind, Duration interval) {
        ProtocolValues.require(interval.toMillis() > 0, "collection interval", "must be positive");
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .daemon()
                .name("poketto-retained-" + kind + "-cleanup")
                .factory());
        scheduler.scheduleWithFixedDelay(() -> collect(action, kind), 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void collect(Runnable action, String kind) {
        try {
            action.run();
        } catch (RetainedCopyException failure) {
            if (failure.reason() == RetainedCopyException.Reason.BUSY) {
                log.debug("Retained {} collection deferred while storage is busy", kind);
            } else {
                log.warn("Retained {} collection failed; data remains for a later attempt", kind, failure);
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Retained storage collector did not stop");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping retained storage collection", failure);
        }
    }
}
