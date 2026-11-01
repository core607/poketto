package io.github.core607.poketto.executor.internal;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** The account lock owns private plan originals and candidates; sandbox paths never address these files. */
final class AccountPlanFiles {
    private static final String MARKER = ".plan-";
    private final Path root;
    private final RetainedDirectory directory;
    private final long maximumBytes;
    private final int maximumFiles;

    AccountPlanFiles(Path root, RetainedDirectory directory, RetainedBaseline.Limits limits) {
        this.root = root;
        this.directory = directory;
        maximumBytes = limits.archiveBytes();
        maximumFiles = Math.multiplyExact(limits.entries(), 2);
    }

    /** Bytes and directory publication are durable before the journal may name the returned identity. */
    void publish(String key, UUID plan, NativePlanFile expected, NativeTransferStore.Source source, Runnable authorize)
            throws IOException {
        authorize.run();
        ensureMarker(key);
        Path target = path(key, plan, expected.sha256());
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            copy(key, plan, expected, OutputStream.nullOutputStream(), authorize);
            return;
        }
        requireCapacity(key, expected.bytes());
        Path temporary = root.resolve(key + ".pending");
        try (var pending = new Pending(temporary)) {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    Set.of(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                var output = new VerifiedOutput(channel, expected, authorize);
                source.write(output);
                output.verify();
                channel.force(true);
            }
            directory.checkFile(temporary);
            authorize.run();
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            RetainedDirectory.sync(root);
        }
    }

    void copy(String key, UUID plan, NativePlanFile expected, OutputStream output, Runnable authorize)
            throws IOException {
        Path path = path(key, plan, expected.sha256());
        directory.checkFile(path);
        authorize.run();
        try (FileChannel channel = FileChannel.open(path, READ, NOFOLLOW_LINKS)) {
            if (channel.size() != expected.bytes()) {
                throw new IOException("Retained plan file length differs from its journal");
            }
            MessageDigest digest = SandcrabProtocol.digest();
            ByteBuffer buffer = ByteBuffer.allocate(65536);
            long remaining = expected.bytes();
            while (remaining > 0) {
                authorize.run();
                buffer.clear().limit((int) Math.min(buffer.capacity(), remaining));
                int count = channel.read(buffer);
                if (count <= 0) {
                    throw new IOException("Retained plan file was truncated");
                }
                digest.update(buffer.array(), 0, count);
                output.write(buffer.array(), 0, count);
                remaining -= count;
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(expected.sha256())) {
                throw new IOException("Retained plan file digest differs from its journal");
            }
            authorize.run();
        }
    }

    /** Only an acknowledged journal transition can release unreferenced plan bytes. */
    void retainOnly(String key, UUID plan, Set<String> digests) throws IOException {
        Path marker = marker(key);
        if (!Files.exists(marker, NOFOLLOW_LINKS)) {
            if (!digests.isEmpty()) {
                throw new IOException("Retained plan inventory marker is missing");
            }
            return;
        }
        directory.checkFile(marker);
        boolean changed = false;
        int retainedCount = 0;
        for (Path entry : entries(key)) {
            String prefix = key + MARKER + plan + "-";
            String name = entry.getFileName().toString();
            boolean retained =
                    plan != null && name.startsWith(prefix) && digests.contains(name.substring(prefix.length()));
            if (!retained) {
                Files.delete(entry);
                changed = true;
            } else {
                retainedCount++;
            }
        }
        if (retainedCount != digests.size()) {
            throw new IOException("Referenced plan files are missing from their owner inventory");
        }
        if (retainedCount == 0) {
            Files.delete(marker);
            changed = true;
        }
        if (changed) {
            RetainedDirectory.sync(root);
        }
    }

    /** A single existence check keeps plan-free command admission from scanning the metadata directory. */
    private Path marker(String key) {
        return root.resolve(ProtocolValues.hex(key, 64, "plan owner key") + ".plans");
    }

    private void ensureMarker(String key) throws IOException {
        Path marker = marker(key);
        if (!Files.exists(marker, NOFOLLOW_LINKS)) {
            try (FileChannel channel = FileChannel.open(
                    marker,
                    Set.of(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                channel.force(true);
            }
            RetainedDirectory.sync(root);
        }
        directory.checkFile(marker);
    }

    private void requireCapacity(String key, long bytes) throws IOException {
        List<Path> entries = entries(key);
        long used = 0;
        for (Path path : entries) {
            used = Math.addExact(used, Files.size(path));
        }
        if (entries.size() >= maximumFiles || bytes > maximumBytes - used) {
            throw new RetainedRecordFiles.SizeLimit();
        }
    }

    private List<Path> entries(String key) throws IOException {
        var found = new ArrayList<Path>();
        try (var stream = Files.newDirectoryStream(root, key + MARKER + "*")) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (!name.matches("[0-9a-f]{64}\\.plan-[0-9a-f-]{36}-[0-9a-f]{64}")) {
                    throw new IOException("Invalid plan file address");
                }
                directory.checkFile(path);
                if (found.size() >= maximumFiles) {
                    throw new IOException("Plan file inventory exceeds its entry bound");
                }
                found.add(path);
            }
        }
        return List.copyOf(found);
    }

    private Path path(String key, UUID plan, String digest) {
        ProtocolValues.hex(key, 64, "plan owner key");
        ProtocolValues.hex(digest, 64, "plan file digest");
        Objects.requireNonNull(plan, "plan identity must be present");
        return root.resolve(key + MARKER + plan + "-" + digest);
    }

    private record Pending(Path path) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
        }
    }

    private static final class VerifiedOutput extends OutputStream {
        private final FileChannel channel;
        private final NativePlanFile expected;
        private final Runnable authorize;
        private final MessageDigest digest = SandcrabProtocol.digest();
        private long written;

        VerifiedOutput(FileChannel channel, NativePlanFile expected, Runnable authorize) {
            this.channel = channel;
            this.expected = expected;
            this.authorize = authorize;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            authorize.run();
            if (length > expected.bytes() - written) {
                throw new IOException("Prepared plan file exceeds its declared length");
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            digest.update(bytes, offset, length);
            written += length;
        }

        void verify() throws IOException {
            authorize.run();
            if (written != expected.bytes()
                    || !HexFormat.of().formatHex(digest.digest()).equals(expected.sha256())) {
                throw new IOException("Prepared plan file differs from its declared identity");
            }
        }
    }
}
