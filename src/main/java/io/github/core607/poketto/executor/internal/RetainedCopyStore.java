package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.ProtocolValues.require;
import static io.github.core607.poketto.executor.internal.RetainedCopyException.Reason.BUSY;
import static io.github.core607.poketto.executor.internal.RetainedCopyException.Reason.EXPIRED;
import static io.github.core607.poketto.executor.internal.RetainedCopyException.Reason.LIMIT;
import static io.github.core607.poketto.executor.internal.RetainedCopyException.Reason.MISSING;
import static io.github.core607.poketto.executor.internal.RetainedCopyException.Reason.STALE;
import static io.github.core607.poketto.executor.internal.RetainedCopyException.Reason.UNAVAILABLE;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;

/** Linux-only private metadata store. The caller reauthorizes and confirms worker fencing separately. */
final class RetainedCopyStore {
    private final Path root;
    private final Limits limits;
    private final Clock clock;
    private final RetainedRecordFiles records;
    private final int uid;

    RetainedCopyStore(Path root, Limits limits, Clock clock) {
        require(root.isAbsolute(), "retention root", "must be absolute");
        this.root = root.normalize();
        this.limits = Objects.requireNonNull(limits, "retention limits must be present");
        this.clock = Objects.requireNonNull(clock, "retention clock must be present");
        this.records = new RetainedRecordFiles(limits.recordBytes());
        try {
            uid = (Integer) Files.getAttribute(Path.of("/proc/self"), "unix:uid");
            checkAncestors(this.root.getParent());
            try {
                Files.createDirectory(
                        this.root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                syncDirectory(this.root.getParent());
            } catch (FileAlreadyExistsException existing) {
                // The existing private root is verified before its lock or records are opened.
            }
            checkRoot();
        } catch (IOException failure) {
            throw new RetainedCopyException(UNAVAILABLE, failure);
        }
    }

    RetainedCopyRecord read(RetainedCopyRecord.Owner owner, UUID copyId) {
        return locked(() -> {
            var record = load(owner, copyId);
            requireLive(record);
            return record;
        });
    }

    /** Hold across the entire command and its host writes; reload the generation after acquisition. */
    RetainedFileLocks.Held writer(RetainedCopyRecord.Owner owner, UUID copyId) {
        return locked(() -> {
            Path path = root.resolve(".writer-" + path(owner, copyId).getFileName());
            Usage usage = usage();
            if (!Files.exists(path, NOFOLLOW_LINKS) && usage.writers() >= limits.copies()) {
                throw new RetainedCopyException(LIMIT);
            }
            return RetainedFileLocks.acquire(path, () -> openLock(path));
        });
    }

    void create(RetainedCopyRecord record) {
        locked(() -> {
            require(
                    record.revision() == 0 && record.generation() == 1,
                    "new retained copy",
                    "must start at revision 0 and generation 1");
            validateExpiry(record);
            Path target = path(record.owner(), record.copyId());
            if (Files.exists(target, NOFOLLOW_LINKS)) {
                throw new RetainedCopyException(STALE);
            }
            Usage usage = usage();
            if (usage.copies() >= limits.copies()) {
                throw new RetainedCopyException(LIMIT);
            }
            publish(target, record, usage.bytes());
            return null;
        });
    }

    void replace(long expectedRevision, long expectedGeneration, RetainedCopyRecord next) {
        locked(() -> {
            var current = load(next.owner(), next.copyId());
            requireLive(current);
            checkVersion(current, expectedRevision, expectedGeneration);
            validateSuccessor(current, next);
            validateExpiry(next);
            publish(path(next.owner(), next.copyId()), next, usage().bytes());
            return null;
        });
    }

    /** Explicit discard is version-checked even after expiry; cleanup cannot delete a newer owner. */
    void discard(RetainedCopyRecord.Owner owner, UUID copyId, long revision, long generation) {
        locked(() -> {
            checkVersion(load(owner, copyId), revision, generation);
            Files.delete(path(owner, copyId));
            syncDirectory(root);
            return null;
        });
    }

    private static void checkVersion(RetainedCopyRecord current, long revision, long generation) {
        if (current.revision() != revision || current.generation() != generation) {
            throw new RetainedCopyException(STALE);
        }
    }

    private static void validateSuccessor(RetainedCopyRecord current, RetainedCopyRecord next) {
        require(next.revision() == current.revision() + 1, "record revision", "must advance exactly once");
        require(
                next.generation() == current.generation() || next.generation() == current.generation() + 1,
                "writer generation",
                "must stay current or advance exactly once");
        require(
                next.transportHash().equals(current.transportHash()) || next.generation() > current.generation(),
                "ownership transfer",
                "must advance the writer generation");
        require(
                next.writer().equals(current.writer()) || next.generation() > current.generation(),
                "worker lease transfer",
                "must advance the writer generation");
        require(next.fullRead() == current.fullRead(), "retained scope", "must not change during recovery");
        require(
                next.acknowledged()
                        .state()
                        .originalCommit()
                        .equals(current.acknowledged().state().originalCommit()),
                "retained baseline",
                "must not change during recovery");
    }

    private void requireLive(RetainedCopyRecord record) {
        if (record.expiresAt() <= clock.millis()) {
            throw new RetainedCopyException(EXPIRED);
        }
    }

    private void validateExpiry(RetainedCopyRecord record) {
        requireLive(record);
        require(
                record.expiresAt() - clock.millis() <= limits.retention().toMillis(),
                "retention expiry",
                "exceeds the configured lifetime");
    }

    private RetainedCopyRecord load(RetainedCopyRecord.Owner owner, UUID copyId) throws IOException {
        Path target = path(owner, copyId);
        try {
            checkFile(target);
        } catch (NoSuchFileException absent) {
            throw new RetainedCopyException(MISSING);
        }
        var record = records.read(target);
        if (!record.owner().equals(owner) || !record.copyId().equals(copyId)) {
            throw new IOException("retained record identity does not match its address");
        }
        return record;
    }

    private void publish(Path target, RetainedCopyRecord record, long used) throws IOException {
        long available = Math.min(
                limits.totalBytes() - used, Files.getFileStore(root).getUsableSpace() - limits.diskReserveBytes());
        Path temporary = root.resolve(".pending-" + UUID.randomUUID());
        boolean published = false;
        try {
            records.write(temporary, record, available);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            published = true;
            syncDirectory(root);
        } catch (IOException failure) {
            if (published) {
                throw new RetainedCopyException(RetainedCopyException.Reason.UNCERTAIN, failure);
            }
            throw failure;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Usage usage() throws IOException {
        long bytes = 0;
        int copies = 0;
        int writers = 0;
        int entries = 0;
        boolean cleaned = false;
        try (var files = Files.newDirectoryStream(root)) {
            for (Path file : files) {
                if (++entries > limits.copies() * 3 + 16) {
                    throw new RetainedCopyException(LIMIT);
                }
                String name = file.getFileName().toString();
                checkFile(file);
                if (name.equals(".lock")) {
                    continue;
                }
                if (name.matches("\\.writer-[0-9a-f]{64}_[0-9a-f-]{36}\\.record")) {
                    validateFileId(name.substring(73, 109));
                    if (retainWriterFile(file)) {
                        writers++;
                    } else {
                        cleaned = true;
                    }
                } else if (name.startsWith(".pending-")) {
                    validateFileId(name.substring(9));
                    Files.delete(file);
                    cleaned = true;
                } else if (name.matches("[0-9a-f]{64}_[0-9a-f-]{36}\\.record")) {
                    validateFileId(name.substring(65, 101));
                    copies++;
                    long size = Files.size(file);
                    if (size < RetainedRecordFiles.OVERHEAD || size > limits.recordBytes()) {
                        throw new IOException("retained record size is outside its configured bounds");
                    }
                    bytes += size;
                } else {
                    throw new IOException("unexpected retention directory entry");
                }
            }
        }
        if (cleaned) {
            syncDirectory(root);
        }
        return new Usage(copies, writers, bytes);
    }

    private boolean retainWriterFile(Path file) throws IOException {
        if (Files.exists(root.resolve(file.getFileName().toString().substring(8)), NOFOLLOW_LINKS)) {
            return true;
        }
        // Every writer acquisition holds .lock before opening its file. Under that same lock,
        // an unowned orphan can be deleted without leaving a waiter on an obsolete inode.
        try (var lock = RetainedFileLocks.acquire(file, () -> openLock(file))) {
            lock.requireValid();
            Files.delete(file);
            return false;
        } catch (RetainedCopyException contention) {
            if (contention.reason() != BUSY) {
                throw contention;
            }
            return true;
        }
    }

    private synchronized <T> T locked(Action<T> action) {
        try {
            checkRoot();
            Path path = root.resolve(".lock");
            try (var lock = RetainedFileLocks.acquire(path, () -> openLock(path))) {
                lock.requireValid();
                return action.run();
            }
        } catch (OverlappingFileLockException busy) {
            throw new RetainedCopyException(BUSY, busy);
        } catch (IOException | JacksonException failure) {
            Throwable cursor = failure;
            for (int depth = 0; cursor != null && depth < 16; depth++, cursor = cursor.getCause()) {
                if (cursor instanceof RetainedRecordFiles.SizeLimit) {
                    throw new RetainedCopyException(LIMIT, failure);
                }
            }
            throw new RetainedCopyException(UNAVAILABLE, failure);
        }
    }

    private FileChannel openLock(Path path) throws IOException {
        FileChannel channel = FileChannel.open(
                path,
                Set.of(CREATE, WRITE, NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            checkFile(path);
            return channel;
        } catch (IOException failure) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static void validateFileId(String value) throws IOException {
        try {
            ProtocolValues.uuid(value, "retained file name");
        } catch (IllegalArgumentException invalid) {
            throw new IOException("retention entry name is invalid", invalid);
        }
    }

    private void checkRoot() throws IOException {
        checkAncestors(root);
        var attributes = Files.readAttributes(root, PosixFileAttributes.class, NOFOLLOW_LINKS);
        if (!ownedWithMode(root, attributes, "rwx------")) {
            throw new IOException("retention root must be private and owned by the application");
        }
    }

    private void checkAncestors(Path directory) throws IOException {
        Path cursor = directory.getRoot();
        for (Path part : directory) {
            cursor = cursor.resolve(part);
            var attributes = Files.readAttributes(cursor, PosixFileAttributes.class, NOFOLLOW_LINKS);
            int mode = (Integer) Files.getAttribute(cursor, "unix:mode", NOFOLLOW_LINKS);
            int owner = (Integer) Files.getAttribute(cursor, "unix:uid", NOFOLLOW_LINKS);
            if (!trustedAncestor(cursor, attributes, mode, owner)) {
                throw new IOException("retention path has an untrusted directory component");
            }
        }
    }

    private void checkFile(Path file) throws IOException {
        var attributes = Files.readAttributes(file, PosixFileAttributes.class, NOFOLLOW_LINKS);
        if (!privateUnaliasedFile(file, attributes)) {
            throw new IOException("retention entry must be a private unaliased application-owned file");
        }
    }

    private boolean trustedAncestor(Path path, PosixFileAttributes attributes, int mode, int owner) throws IOException {
        boolean writable = (mode & 0022) != 0;
        boolean protectedParent = (mode & 01000) != 0 && (owner == 0 || owner == uid);
        return attributes.isDirectory()
                && path.toRealPath().equals(path)
                && (!writable || protectedParent)
                && (owner == 0 || owner == uid);
    }

    private boolean ownedWithMode(Path path, PosixFileAttributes attributes, String mode) throws IOException {
        return attributes.permissions().equals(PosixFilePermissions.fromString(mode))
                && Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS).equals(uid);
    }

    private boolean privateUnaliasedFile(Path file, PosixFileAttributes attributes) throws IOException {
        return attributes.isRegularFile()
                && !attributes.isSymbolicLink()
                && ownedWithMode(file, attributes, "rw-------")
                && Files.getAttribute(file, "unix:nlink", NOFOLLOW_LINKS).equals(1);
    }

    private Path path(RetainedCopyRecord.Owner owner, UUID copyId) {
        String value = owner.subjectId() + ":" + owner.workspaceId();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            return root.resolve(HexFormat.of().formatHex(hash) + "_" + copyId + ".record");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, READ, NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }

    record Limits(int copies, long recordBytes, long totalBytes, long diskReserveBytes, Duration retention) {
        Limits {
            ProtocolValues.inRange(copies, 1, 1024, "retained copy count");
            ProtocolValues.inRange(recordBytes, 4096, 256L * 1024 * 1024, "retained record bytes");
            ProtocolValues.inRange(totalBytes, recordBytes, 1024L * 1024 * 1024 * 1024, "retained total bytes");
            require(diskReserveBytes >= 0, "disk reserve", "must not be negative");
            Objects.requireNonNull(retention, "retention lifetime must be present");
            require(
                    retention.compareTo(Duration.ofMillis(1)) >= 0 && retention.compareTo(Duration.ofDays(30)) <= 0,
                    "retention lifetime",
                    "must be positive and at most 30 days");
        }
    }

    private record Usage(int copies, int writers, long bytes) {}

    @FunctionalInterface
    private interface Action<T> {
        T run() throws IOException;
    }
}
