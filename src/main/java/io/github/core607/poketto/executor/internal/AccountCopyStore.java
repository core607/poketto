package io.github.core607.poketto.executor.internal;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One private journal per account/workspace/read scope, using the existing bounded durable codec. */
final class AccountCopyStore {
    private final Path root;
    private final RetainedDirectory directory;
    private final RetainedRecordFiles files;
    private final Limits limits;
    private final Clock clock;

    AccountCopyStore(Path root, Limits limits, Clock clock) {
        ProtocolValues.require(root.isAbsolute(), "account copy root", "must be absolute");
        this.root = root.normalize();
        this.limits = Objects.requireNonNull(limits);
        this.clock = Objects.requireNonNull(clock);
        files = new RetainedRecordFiles(limits.recordBytes());
        try {
            directory = new RetainedDirectory(this.root);
            var storage = Files.getFileStore(this.root);
            ProtocolValues.require(
                    storage.type().equals("xfs") && storage.getTotalSpace() <= limits.poolBytes(),
                    "metadata storage",
                    "must belong to the bounded XFS executor pool");
        } catch (IOException failure) {
            throw unavailable(failure);
        }
    }

    long nextExpiry() {
        return Math.addExact(clock.millis(), limits.idle().toMillis());
    }

    boolean expired(AccountCopyRecord record) {
        return record.expiresAt() <= clock.millis();
    }

    Lease acquire(AccountCopyRecord.Owner owner) {
        Path lock = root.resolve(key(owner) + ".lock");
        try (var index = index()) {
            index.requireValid();
            cleanPending();
            if (!Files.exists(lock, NOFOLLOW_LINKS) && countOwners() >= limits.copies()) {
                throw new RetainedCopyException(RetainedCopyException.Reason.LIMIT);
            }
            var held = RetainedFileLocks.acquire(lock, () -> directory.openLock(lock));
            try {
                return new Lease(owner, held, load(owner));
            } catch (RuntimeException | IOException failure) {
                held.close();
                throw failure;
            }
        } catch (IOException failure) {
            throw unavailable(failure);
        }
    }

    private AccountCopyRecord load(AccountCopyRecord.Owner owner) throws IOException {
        Path path = recordPath(owner);
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            return null;
        }
        directory.checkFile(path);
        AccountCopyRecord record = files.read(path, AccountCopyRecord.class);
        ProtocolValues.require(record.owner().equals(owner), "copy owner", "must match its journal address");
        return record;
    }

    private RetainedFileLocks.Held index() throws IOException {
        directory.checkRoot();
        Path lock = root.resolve(".index.lock");
        return RetainedFileLocks.acquire(lock, () -> directory.openLock(lock));
    }

    private int countOwners() throws IOException {
        int count = 0;
        try (var entries = Files.newDirectoryStream(root, "*.lock")) {
            for (Path path : entries) {
                directory.checkFile(path);
                if (!path.getFileName().toString().equals(".index.lock")) {
                    if (!path.getFileName().toString().matches("[0-9a-f]{64}\\.lock")) {
                        throw new IOException("Invalid owner lock address");
                    }
                    if (removeUnusedLock(path)) {
                        continue;
                    }
                    if (++count > limits.copies()) {
                        throw new RetainedCopyException(RetainedCopyException.Reason.LIMIT);
                    }
                }
            }
        }
        return count;
    }

    private boolean removeUnusedLock(Path path) throws IOException {
        String name = path.getFileName().toString();
        Path record = root.resolve(name.substring(0, 64) + ".account");
        if (Files.exists(record, NOFOLLOW_LINKS)) {
            return false;
        }
        try (var unused = RetainedFileLocks.acquire(path, () -> directory.openLock(path))) {
            unused.requireValid();
            Files.delete(path);
            RetainedDirectory.sync(root);
            return true;
        } catch (RetainedCopyException busy) {
            if (busy.reason() != RetainedCopyException.Reason.BUSY) {
                throw busy;
            }
            return false;
        }
    }

    private void cleanPending() throws IOException {
        try (var entries = Files.newDirectoryStream(root, ".pending-*")) {
            for (Path path : entries) {
                ProtocolValues.uuid(path.getFileName().toString().substring(9), "pending record identity");
                directory.checkFile(path);
                Files.delete(path);
            }
        }
    }

    private Path recordPath(AccountCopyRecord.Owner owner) {
        return root.resolve(key(owner) + ".account");
    }

    private static String key(AccountCopyRecord.Owner owner) {
        String value = owner.accountId() + ":" + owner.workspaceId() + ":" + owner.fullRead();
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static RetainedCopyException unavailable(IOException failure) {
        return new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE, failure);
    }

    final class Lease implements AutoCloseable {
        private final AccountCopyRecord.Owner owner;
        private final RetainedFileLocks.Held held;
        private AccountCopyRecord current;
        private boolean uncertain;

        private Lease(AccountCopyRecord.Owner owner, RetainedFileLocks.Held held, AccountCopyRecord current) {
            this.owner = owner;
            this.held = held;
            this.current = current;
        }

        Optional<AccountCopyRecord> record() {
            requireUsable();
            return Optional.ofNullable(current);
        }

        void write(AccountCopyRecord next) {
            requireUsable();
            requireNext(next);
            Path temporary = root.resolve(".pending-" + UUID.randomUUID());
            try (var index = index()) {
                index.requireValid();
                try {
                    files.write(temporary, next, limits.recordBytes());
                    directory.checkFile(temporary);
                    Files.move(
                            temporary,
                            recordPath(owner),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                    RetainedDirectory.sync(root);
                    current = next;
                } finally {
                    Files.deleteIfExists(temporary);
                }
            } catch (IOException failure) {
                uncertain = true;
                throw unavailable(failure);
            }
        }

        private void requireNext(AccountCopyRecord next) {
            ProtocolValues.require(next.owner().equals(owner), "copy owner", "must match the held account");
            ProtocolValues.require(
                    next.revision() == (current == null ? 0 : current.revision() + 1),
                    "copy revision",
                    "must advance the held journal");
            ProtocolValues.require(
                    current == null || current.copyId().equals(next.copyId()),
                    "copy identity",
                    "must not replace existing work");
            if (current != null) {
                ProtocolValues.require(
                        current.state().originalCommit().equals(next.state().originalCommit()),
                        "original commit",
                        "must remain pinned");
                ProtocolValues.require(
                        current.original() == null || current.original().equals(next.original()),
                        "original archive",
                        "must remain immutable");
                ProtocolValues.require(
                        Objects.equals(current.publicExport(), next.publicExport()),
                        "public projection",
                        "must remain pinned");
                ProtocolValues.require(
                        next.expiresAt() >= current.expiresAt(), "copy expiry", "must not move backwards");
            }
            long latest = Math.max(nextExpiry(), current == null ? 0 : current.expiresAt());
            ProtocolValues.require(next.expiresAt() <= latest, "copy expiry", "exceeds idle retention");
        }

        /** Caller first confirms worker disposal; deleting metadata does not delete disk work. */
        void remove(UUID expectedCopyId) {
            requireUsable();
            ProtocolValues.require(
                    current != null && current.copyId().equals(expectedCopyId),
                    "discard identity",
                    "must match the held copy");
            try (var index = index()) {
                index.requireValid();
                Files.delete(recordPath(owner));
                RetainedDirectory.sync(root);
                current = null;
            } catch (IOException failure) {
                uncertain = true;
                throw unavailable(failure);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                if (current == null) {
                    try (var index = index()) {
                        index.requireValid();
                        if (!Files.exists(recordPath(owner), NOFOLLOW_LINKS)) {
                            Files.deleteIfExists(root.resolve(key(owner) + ".lock"));
                            RetainedDirectory.sync(root);
                        }
                    }
                }
            } finally {
                held.close();
            }
        }

        private void requireUsable() {
            held.requireValid();
            if (uncertain) {
                throw new RetainedCopyException(RetainedCopyException.Reason.UNCERTAIN);
            }
        }
    }

    record Limits(int copies, long recordBytes, long poolBytes, Duration idle) {
        Limits {
            ProtocolValues.inRange(copies, 1, 1024, "stored copy count");
            ProtocolValues.inRange(recordBytes, 4096, 64L * 1024 * 1024, "copy journal bytes");
            ProtocolValues.inRange(poolBytes, recordBytes, 1024L * 1024 * 1024 * 1024, "executor pool bytes");
            ProtocolValues.require(
                    idle != null && !idle.isNegative() && !idle.isZero() && idle.compareTo(Duration.ofDays(30)) <= 0,
                    "copy idle duration",
                    "must be positive and at most 30 days");
        }
    }
}
