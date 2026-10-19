package io.github.core607.poketto.executor.internal;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** One channel per held inode: closing a competing channel can release POSIX process locks. */
final class RetainedFileLocks {
    private static final Map<Path, LocalLock> LOCAL = new HashMap<>();

    private RetainedFileLocks() {}

    static Held acquire(Path path, Opener opener) throws IOException {
        return open(reserve(path), opener);
    }

    private static Held open(Reservation reservation, Opener opener) throws IOException {
        FileChannel channel = null;
        try {
            channel = opener.open();
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new RetainedCopyException(RetainedCopyException.Reason.BUSY);
            }
            return new Held(reservation, channel, lock);
        } catch (IOException | OverlappingFileLockException | RetainedCopyException failure) {
            failedOpen(reservation, channel, failure);
            throw failure;
        }
    }

    /** Index users may wait; owner locks remain nonblocking while an index lock is held. */
    static Held await(Path path, Opener opener, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Reservation reservation = awaitReservation(path, deadline);
            try {
                return open(reservation, opener);
            } catch (RetainedCopyException busy) {
                if (busy.reason() != RetainedCopyException.Reason.BUSY || System.nanoTime() - deadline >= 0) {
                    throw busy;
                }
                try {
                    // Only another process can still own the file after local admission.
                    TimeUnit.NANOSECONDS.sleep(
                            Math.min(TimeUnit.MILLISECONDS.toNanos(10), deadline - System.nanoTime()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RetainedCopyException(RetainedCopyException.Reason.BUSY, interrupted);
                }
            }
        }
    }

    private static Reservation reserve(Path path) {
        Reservation reservation = retain(path);
        if (reservation.local().permit.tryAcquire()) {
            return reservation;
        }
        reservation.forget();
        throw new RetainedCopyException(RetainedCopyException.Reason.BUSY);
    }

    private static Reservation awaitReservation(Path path, long deadline) {
        Reservation reservation = retain(path);
        boolean acquired = false;
        try {
            acquired = reservation
                    .local()
                    .permit
                    .tryAcquire(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (!acquired) {
                throw new RetainedCopyException(RetainedCopyException.Reason.BUSY);
            }
            return reservation;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RetainedCopyException(RetainedCopyException.Reason.BUSY, interrupted);
        } finally {
            if (!acquired) {
                reservation.forget();
            }
        }
    }

    private static Reservation retain(Path path) {
        if (Thread.currentThread().isInterrupted()) {
            throw new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE);
        }
        synchronized (LOCAL) {
            LocalLock local = LOCAL.computeIfAbsent(path, ignored -> new LocalLock());
            local.references++;
            return new Reservation(path, local);
        }
    }

    private static void failedOpen(Reservation reservation, FileChannel channel, Exception failure) {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        } finally {
            reservation.release();
        }
    }

    private static final class LocalLock {
        private final Semaphore permit = new Semaphore(1, true);
        private int references;
    }

    /** Waiters retain the same queue until they leave; unused paths do not accumulate. */
    private record Reservation(Path path, LocalLock local) {
        void release() {
            local.permit.release();
            forget();
        }

        void forget() {
            synchronized (LOCAL) {
                if (--local.references == 0) {
                    LOCAL.remove(path);
                }
            }
        }
    }

    @FunctionalInterface
    interface Opener {
        FileChannel open() throws IOException;
    }

    static final class Held implements AutoCloseable {
        private final Reservation reservation;
        private final FileChannel channel;
        private final FileLock lock;
        private boolean closed;

        private Held(Reservation reservation, FileChannel channel, FileLock lock) {
            this.reservation = reservation;
            this.channel = channel;
            this.lock = lock;
        }

        void requireValid() {
            if (!lock.isValid()) {
                throw new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE);
            }
        }

        void requirePath(Path expected) {
            if (!reservation.path().equals(expected)) {
                throw new RetainedCopyException(RetainedCopyException.Reason.STALE);
            }
            requireValid();
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            try (channel) {
                lock.release();
            } finally {
                closed = true;
                reservation.release();
            }
        }
    }
}
