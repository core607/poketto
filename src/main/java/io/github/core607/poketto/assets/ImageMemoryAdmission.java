package io.github.core607.poketto.assets;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Shared conservative working-set reservations; byte limits do not measure the JVM's total heap. */
public final class ImageMemoryAdmission {
    public static final long BROWSER_BYTES = 64L * 1024 * 1024;
    public static final long MCP_BYTES = 256L * 1024 * 1024;
    private static final long UNIT = 1024 * 1024;
    private final int capacity;
    private final int maximumWaiters;
    private final long waitNanos;
    private final Semaphore available;
    private final Semaphore waiters;
    private final AtomicLong rejected = new AtomicLong();

    public ImageMemoryAdmission(long bytes, int maximumWaiters, Duration wait) {
        if (bytes < UNIT
                || bytes > 2048 * UNIT
                || bytes % UNIT != 0
                || maximumWaiters < 1
                || maximumWaiters > 64
                || wait.isNegative()
                || wait.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException(
                    "image admission requires 1 to 2048 whole MiB, 1 to 64 waiters and at most five seconds waiting");
        }
        this.capacity = Math.toIntExact(bytes / UNIT);
        this.maximumWaiters = maximumWaiters;
        this.waitNanos = wait.toNanos();
        this.available = new Semaphore(capacity, true);
        this.waiters = new Semaphore(maximumWaiters);
    }

    /** Waiting callers have not allocated full images; both wait time and waiter count are bounded. */
    public Optional<ImageRequestScope> acquire(long bytes) {
        return acquire(bytes, true);
    }

    /** Image inspection under repository locks must fail immediately instead of queueing per image. */
    public Optional<ImageRequestScope> tryAcquire(long bytes) {
        return acquire(bytes, false);
    }

    private Optional<ImageRequestScope> acquire(long bytes, boolean mayWait) {
        if (bytes <= 0 || bytes % UNIT != 0) throw new IllegalArgumentException("image reservation must use whole MiB");
        int units = Math.toIntExact(bytes / UNIT);
        if (units > capacity) return rejected();
        try {
            if (available.tryAcquire(units, 0, TimeUnit.NANOSECONDS)) return reservation(units);
            if (!mayWait) return rejected();
            if (!waiters.tryAcquire()) return rejected();
            try {
                return available.tryAcquire(units, waitNanos, TimeUnit.NANOSECONDS) ? reservation(units) : rejected();
            } finally {
                waiters.release();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return rejected();
        }
    }

    public long reservedBytes() {
        return (capacity - available.availablePermits()) * UNIT;
    }

    public int waitingRequests() {
        return maximumWaiters - waiters.availablePermits();
    }

    public long rejectedRequests() {
        return rejected.get();
    }

    private Optional<ImageRequestScope> reservation(int units) {
        return Optional.of(new ImageRequestScope(() -> available.release(units)));
    }

    private Optional<ImageRequestScope> rejected() {
        rejected.updateAndGet(value -> value == Long.MAX_VALUE ? value : value + 1);
        return Optional.empty();
    }
}
