package io.github.core607.poketto.content.internal;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes a complete bounded ZIP into caller-owned staging; the caller publishes only on success. */
final class PortableArchiveWriter {
    @FunctionalInterface
    interface Source {
        void copyTo(OutputStream output) throws IOException;
    }

    record Entry(String path, long bytes, Source source) {
        Entry {
            if (path == null || path.isEmpty() || path.length() > 1024)
                throw new IllegalArgumentException("invalid export path");
            for (String segment : path.split("/", -1)) RepositoryPathRules.validate(segment);
            if (RepositoryPathRules.reserved(path) || bytes < 0)
                throw new IllegalArgumentException("invalid export entry");
            Objects.requireNonNull(source);
        }
    }

    record Limits(int entries, long sourceBytes, long zipBytes, Duration timeout) {
        Limits {
            if (entries < 1
                    || entries > 10_000
                    || sourceBytes < 1
                    || zipBytes < 1
                    || timeout.isNegative()
                    || timeout.isZero()
                    || timeout.compareTo(Duration.ofMinutes(10)) > 0)
                throw new IllegalArgumentException("invalid export bounds");
        }
    }

    static void write(OutputStream staging, List<Entry> entries, Limits limits, Runnable authorize) throws IOException {
        entries = List.copyOf(entries);
        if (entries.isEmpty() || entries.size() > limits.entries())
            throw new IllegalArgumentException("export entry bound exceeded");
        var names = new HashSet<String>();
        long bytes = 0;
        for (Entry entry : entries) {
            if (entry.bytes() > limits.sourceBytes() - bytes)
                throw new IllegalArgumentException("export source byte bound exceeded");
            bytes += entry.bytes();
            if (!names.add(DocumentPathRules.collisionKey(entry.path())))
                throw new IllegalArgumentException("export path collision");
        }
        for (String name : names) {
            for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1)) {
                if (names.contains(name.substring(0, slash)))
                    throw new IllegalArgumentException("export file and directory collision");
            }
        }
        long deadline = System.nanoTime() + limits.timeout().toNanos();
        Runnable deadlineCheck = () -> {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
                throw new IllegalStateException("export deadline exceeded");
        };
        Runnable check = () -> {
            deadlineCheck.run();
            authorize.run();
        };
        check.run();
        // The owning store controls staging cleanup and durability, including after ZIP close fails.
        var boundedZip = new CountedOutput(staging, limits.zipBytes(), deadlineCheck);
        try (var zip = new ZipOutputStream(boundedZip)) {
            for (Entry entry : entries) {
                check.run();
                var header = new ZipEntry(entry.path());
                header.setTime(0);
                zip.putNextEntry(header);
                var content = new CountedOutput(zip, entry.bytes(), check);
                entry.source().copyTo(content);
                if (content.bytes != entry.bytes()) throw new IOException("export original is incomplete");
                zip.closeEntry();
            }
            check.run();
            zip.finish();
            check.run();
        }
    }

    private static final class CountedOutput extends FilterOutputStream {
        private final long maximum;
        private final Runnable check;
        private long bytes;

        CountedOutput(OutputStream output, long maximum, Runnable check) {
            super(output);
            this.maximum = maximum;
            this.check = check;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, data.length);
            if (length > maximum - bytes) throw new IOException("export byte bound exceeded");
            for (int sent = 0; sent < length; ) {
                check.run();
                int count = Math.min(65536, length - sent);
                out.write(data, offset + sent, count);
                bytes += count;
                sent += count;
            }
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
