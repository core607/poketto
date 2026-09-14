package io.github.core607.poketto.executor.internal;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded journal batches run separately from worker lease renewal. */
final class AccountCopyMaintenance implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AccountCopyMaintenance.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("poketto-account-copy-cleanup").factory());

    AccountCopyMaintenance(IsolatedRepositoryExecutor executor, Duration interval) {
        ProtocolValues.require(interval.toMillis() > 0, "collection interval", "must be positive");
        scheduler.scheduleWithFixedDelay(
                () -> collect(executor), interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void collect(IsolatedRepositoryExecutor executor) {
        try {
            executor.collectExpiredCopies();
        } catch (RetainedCopyException failure) {
            if (failure.reason() == RetainedCopyException.Reason.BUSY) {
                log.debug("Account journal collection deferred while storage is busy");
            } else {
                log.warn("Account journal collection will retry", failure);
            }
        } catch (RuntimeException failure) {
            log.warn("Account collection could not complete this batch", failure);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Account copy collector did not stop");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping account copy collector", interrupted);
        }
    }
}
