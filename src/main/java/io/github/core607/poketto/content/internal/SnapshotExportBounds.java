package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import org.eclipse.jgit.lib.ProgressMonitor;

/** Byte, time and cancellation bounds for snapshot bundle output and Git packing. */
final class SnapshotExportBounds {
    private SnapshotExportBounds() {}

    static void checkDeadline(long deadline) {
        if (System.nanoTime() - deadline > 0 || Thread.currentThread().isInterrupted()) {
            throw unavailable();
        }
    }

    private static ContentRepositoryException unavailable() {
        return new ContentRepositoryException("repository execution snapshot could not be exported within its bounds");
    }

    static final class BoundedOutput extends FilterOutputStream {
        private final long maximum;
        private final long deadline;
        private long bytes;

        BoundedOutput(OutputStream output, long maximum, long deadline) {
            super(output);
            this.maximum = maximum;
            this.deadline = deadline;
        }

        @Override
        public void write(int value) throws IOException {
            checkDeadline(deadline);
            if (++bytes > maximum) {
                throw unavailable();
            }
            out.write(value);
        }

        @Override
        public void write(byte[] value, int offset, int length) throws IOException {
            checkDeadline(deadline);
            if (length > maximum - bytes) {
                throw unavailable();
            }
            bytes += length;
            out.write(value, offset, length);
        }
    }

    record DeadlineMonitor(long deadline) implements ProgressMonitor {
        @Override
        public void start(int tasks) {
            checkDeadline(deadline);
        }

        @Override
        public void beginTask(String title, int work) {
            checkDeadline(deadline);
        }

        @Override
        public void update(int completed) {
            checkDeadline(deadline);
        }

        @Override
        public void endTask() {
            checkDeadline(deadline);
        }

        @Override
        public boolean isCancelled() {
            return System.nanoTime() - deadline > 0 || Thread.currentThread().isInterrupted();
        }

        @Override
        public void showDuration(boolean enabled) {}
    }
}
