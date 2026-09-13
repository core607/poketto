package io.github.core607.poketto.executor.internal;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** One channel per held inode: closing a competing channel can release POSIX process locks. */
final class RetainedFileLocks {
    private static final Set<Path> HELD = new HashSet<>();

    private RetainedFileLocks() {}

    static Held acquire(Path path, Opener opener) throws IOException {
        reserve(path);
        FileChannel channel = null;
        try {
            channel = opener.open();
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new RetainedCopyException(RetainedCopyException.Reason.BUSY);
            }
            return new Held(path, channel, lock);
        } catch (IOException | OverlappingFileLockException | RetainedCopyException failure) {
            failedOpen(path, channel, failure);
            throw failure;
        }
    }

    private static void reserve(Path path) {
        if (Thread.currentThread().isInterrupted()) {
            throw new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE);
        }
        synchronized (HELD) {
            if (!HELD.add(path)) {
                throw new RetainedCopyException(RetainedCopyException.Reason.BUSY);
            }
        }
    }

    private static void failedOpen(Path path, FileChannel channel, Exception failure) {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        } finally {
            release(path);
        }
    }

    private static void release(Path path) {
        synchronized (HELD) {
            HELD.remove(path);
        }
    }

    @FunctionalInterface
    interface Opener {
        FileChannel open() throws IOException;
    }

    static final class Held implements AutoCloseable {
        private final Path path;
        private final FileChannel channel;
        private final FileLock lock;
        private boolean closed;

        private Held(Path path, FileChannel channel, FileLock lock) {
            this.path = path;
            this.channel = channel;
            this.lock = lock;
        }

        void requireValid() {
            if (!lock.isValid()) {
                throw new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE);
            }
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
                release(path);
            }
        }
    }
}
