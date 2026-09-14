package io.github.core607.poketto.executor.internal;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.core607.poketto.content.RepositoryBaselineLimits;
import io.github.core607.poketto.content.RepositoryFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Private originals have their own quota and creation lock; traversal never holds the metadata lock. */
final class RetainedBaselineStore {
    private final Path root;
    private final RetainedDirectory directory;
    private final RetainedCopyStore records;
    private final Limits limits;

    RetainedBaselineStore(Path root, RetainedCopyStore records, Limits limits) {
        ProtocolValues.require(root.isAbsolute(), "baseline root", "must be absolute");
        this.root = root.normalize();
        this.records = Objects.requireNonNull(records, "retained metadata store must be present");
        this.limits = Objects.requireNonNull(limits, "baseline store limits must be present");
        records.requireSeparateRoot(this.root);
        try {
            directory = new RetainedDirectory(this.root);
        } catch (IOException failure) {
            throw unavailable(failure);
        }
    }

    RetainedBaseline.Reference capture(
            RetainedFileLocks.Held writer,
            RetainedBaseline.Identity identity,
            Consumer<Consumer<RepositoryFile>> source) {
        records.requireBaseline(writer, identity, true);
        return locked(() -> {
            records.requireBaseline(writer, identity, true);
            Path target = path(identity);
            if (Files.exists(target, NOFOLLOW_LINKS)) {
                throw new RetainedCopyException(RetainedCopyException.Reason.STALE);
            }
            Usage usage = usage();
            if (usage.copies() >= limits.copies()) {
                throw new RetainedCopyException(RetainedCopyException.Reason.LIMIT);
            }
            long available = Math.min(
                    limits.files().archiveBytes(),
                    Math.min(
                            limits.totalBytes() - usage.bytes(),
                            Files.getFileStore(root).getUsableSpace() - limits.diskReserveBytes()));
            if (available < 4096) {
                throw new RetainedCopyException(RetainedCopyException.Reason.LIMIT);
            }
            var bounds = new RetainedBaseline.Limits(
                    available, limits.files().expandedBytes(), limits.files().entries());
            return publish(writer, identity, target, bounds, source);
        });
    }

    void requireRecords(RetainedCopyStore expected) {
        ProtocolValues.require(records == expected, "baseline metadata", "must be the same store instance");
    }

    RepositoryBaselineLimits traversalLimits() {
        return new RepositoryBaselineLimits(
                limits.files().entries(),
                Math.min(limits.files().expandedBytes(), 1024L * 1024 * 1024),
                Duration.ofMinutes(2));
    }

    private RetainedBaseline.Reference publish(
            RetainedFileLocks.Held writer,
            RetainedBaseline.Identity identity,
            Path target,
            RetainedBaseline.Limits bounds,
            Consumer<Consumer<RepositoryFile>> source)
            throws IOException {
        Path temporary = root.resolve(".pending-" + UUID.randomUUID());
        boolean published = false;
        try {
            var reference = RetainedBaselineFiles.write(temporary, identity, bounds, source);
            records.requireBaseline(writer, identity, true);
            directory.checkFile(temporary);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            published = true;
            RetainedDirectory.sync(root);
            return reference;
        } catch (IOException failure) {
            if (published) {
                throw new RetainedCopyException(RetainedCopyException.Reason.UNCERTAIN, failure);
            }
            throw failure;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** The writer must remain held for every read; the caller separately enforces current user authorization. */
    Reader open(RetainedFileLocks.Held writer, RetainedBaseline.Reference reference) {
        records.requireBaselineReference(writer, reference);
        try {
            directory.checkRoot();
            Path file = path(reference.identity());
            directory.checkFile(file);
            var reader = RetainedBaselineFiles.open(file, reference, limits.files());
            boolean transferred = false;
            try {
                records.requireBaselineReference(writer, reference);
                var result = new Reader(writer, reference.identity(), reader);
                transferred = true;
                return result;
            } finally {
                if (!transferred) {
                    reader.close();
                }
            }
        } catch (IOException failure) {
            throw unavailable(failure);
        }
    }

    int collectUnused() {
        return locked(() -> {
            usage();
            int removed = 0;
            try (var files = Files.newDirectoryStream(root, "*.baseline")) {
                for (Path file : files) {
                    directory.checkFile(file);
                    var identity =
                            RetainedBaselineFiles.identity(file, limits.files().archiveBytes());
                    if (!file.equals(path(identity))) {
                        throw new IOException("baseline identity does not match its address");
                    }
                    if (records.removeUnusedBaseline(identity, () -> {
                        Files.delete(file);
                        RetainedDirectory.sync(root);
                    })) {
                        removed++;
                    }
                }
            }
            return removed;
        });
    }

    private Usage usage() throws IOException {
        int entries = 0;
        int copies = 0;
        long bytes = 0;
        boolean cleaned = false;
        try (var files = Files.newDirectoryStream(root)) {
            for (Path file : files) {
                if (++entries > limits.copies() * 2 + 8) {
                    throw new RetainedBaselineIo.Limit();
                }
                directory.checkFile(file);
                String name = file.getFileName().toString();
                if (name.equals(".lock")) {
                    continue;
                }
                if (name.startsWith(".pending-")) {
                    validateId(name.substring(9));
                    Files.delete(file);
                    cleaned = true;
                } else if (name.matches("[0-9a-f]{64}_[0-9a-f-]{36}\\.baseline")) {
                    validateId(name.substring(65, 101));
                    long size = Files.size(file);
                    if (size < 64 || size > limits.files().archiveBytes()) {
                        throw new IOException("baseline archive size is outside its bounds");
                    }
                    bytes += size;
                    copies++;
                } else {
                    throw new IOException("unexpected baseline directory entry");
                }
            }
        }
        if (cleaned) {
            RetainedDirectory.sync(root);
        }
        return new Usage(copies, bytes);
    }

    private static void validateId(String id) throws IOException {
        try {
            ProtocolValues.uuid(id, "baseline filename");
        } catch (IllegalArgumentException failure) {
            throw new IOException("baseline filename is invalid", failure);
        }
    }

    private Path path(RetainedBaseline.Identity identity) {
        String owner = identity.owner().subjectId() + ":" + identity.owner().workspaceId();
        String hash =
                HexFormat.of().formatHex(RetainedBaselineIo.sha256().digest(owner.getBytes(StandardCharsets.US_ASCII)));
        return root.resolve(hash + "_" + identity.copyId() + ".baseline");
    }

    private <T> T locked(Action<T> action) {
        try {
            directory.checkRoot();
            Path lock = root.resolve(".lock");
            try (var held = RetainedFileLocks.acquire(lock, () -> directory.openLock(lock))) {
                held.requireValid();
                return action.run();
            }
        } catch (IOException failure) {
            throw unavailable(failure);
        }
    }

    private static RetainedCopyException unavailable(IOException failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof RetainedBaselineIo.Limit) {
                return new RetainedCopyException(RetainedCopyException.Reason.LIMIT, failure);
            }
        }
        return new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE, failure);
    }

    final class Reader implements AutoCloseable {
        private final RetainedFileLocks.Held writer;
        private final RetainedBaseline.Identity identity;
        private final RetainedBaselineFiles.Reader reader;

        private Reader(
                RetainedFileLocks.Held writer,
                RetainedBaseline.Identity identity,
                RetainedBaselineFiles.Reader reader) {
            this.writer = writer;
            this.identity = identity;
            this.reader = reader;
        }

        Optional<RepositoryFile> find(String path) {
            requireLive();
            try {
                var file = reader.find(path);
                requireLive();
                return file;
            } catch (IOException failure) {
                throw unavailable(failure);
            }
        }

        private void requireLive() {
            records.requireBaseline(writer, identity, false);
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    record Limits(int copies, RetainedBaseline.Limits files, long totalBytes, long diskReserveBytes) {
        Limits {
            ProtocolValues.inRange(copies, 1, 1024, "baseline copy limit");
            Objects.requireNonNull(files, "baseline file limits must be present");
            ProtocolValues.inRange(
                    totalBytes, files.archiveBytes(), 1024L * 1024 * 1024 * 1024, "baseline total limit");
            ProtocolValues.require(diskReserveBytes >= 0, "baseline disk reserve", "must not be negative");
        }
    }

    private record Usage(int copies, long bytes) {}

    @FunctionalInterface
    private interface Action<T> {
        T run() throws IOException;
    }
}
