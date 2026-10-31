package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Private disposable transfer files share one bounded allocation ledger and exclusive application owner. */
final class NativeTransferStore implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(NativeTransferStore.class);
    private final Path root;
    private final long maximumBytes;
    private final int maximumFiles;
    private final RetainedDirectory protectedDirectory;
    private final RetainedFileLocks.Held ownership;
    private final Map<Path, File> files = new HashMap<>();
    private long reserved;
    private boolean closed;
    private boolean released;

    NativeTransferStore(Path root, long maximumBytes, int maximumFiles) {
        ProtocolValues.inRange(maximumBytes, 1, 32L * 1024 * 1024 * 1024, "native transfer pool bytes");
        ProtocolValues.inRange(maximumFiles, 1, 4096, "native transfer file count");
        this.root = root.toAbsolutePath().normalize();
        this.maximumBytes = maximumBytes;
        this.maximumFiles = maximumFiles;
        try {
            protectedDirectory = prepareDirectory(this.root);
            Path lock = this.root.resolve(".lock");
            ownership = RetainedFileLocks.acquire(
                    lock,
                    () -> protectedDirectory == null
                            ? FileChannel.open(
                                    lock,
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS)
                            : protectedDirectory.openLock(lock));
            try {
                clearAbandoned();
            } catch (IOException failure) {
                ownership.close();
                throw failure;
            }
        } catch (IOException failure) {
            throw new WorkerUnavailableException(failure, false);
        }
    }

    private static RetainedDirectory prepareDirectory(Path root) throws IOException {
        if (Files.getFileAttributeView(root.getParent(), PosixFileAttributeView.class) != null) {
            return new RetainedDirectory(root);
        }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(root);
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !root.toRealPath().equals(root)) {
            throw new IOException("Native transfer root must be an unaliased directory");
        }
        return null;
    }

    /** Startup owns the lock before deleting only this store's generated, unpublished ephemeral files. */
    private void clearAbandoned() throws IOException {
        try (var entries = Files.newDirectoryStream(root)) {
            int count = 0;
            for (Path path : entries) {
                if (path.getFileName().toString().equals(".lock")) {
                    continue;
                }
                if (++count > 4096 || !path.getFileName().toString().matches("[0-9a-f-]{36}\\.transfer")) {
                    throw new IOException("Native transfer root contains an unrecognized entry");
                }
                deleteDirectory(path);
            }
        }
    }

    /** Source must enforce the bound while writing; snapshot clients validate before installing this absent target. */
    File capture(long maximum, Runnable authorize, Capture source) {
        File file = reserve(maximum);
        try {
            authorize.run();
            source.write(file.path);
            file.verify(authorize);
            file.finishProducing();
            return file;
        } catch (IOException failure) {
            file.abandon(failure);
            throw new WorkerUnavailableException(failure);
        } catch (SandcrabClient.Rejected | AuthException failure) {
            file.abandon(failure);
            throw failure;
        } catch (RuntimeException failure) {
            log.warn("Native transfer capture did not complete", failure);
            file.abandon(failure);
            throw failure;
        }
    }

    File write(long maximum, Runnable authorize, Source source) {
        return capture(maximum, authorize, target -> {
            try (OutputStream output = output(target)) {
                source.write(new BoundedOutput(output, maximum, authorize));
            }
        });
    }

    private OutputStream output(Path target) throws IOException {
        var options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileChannel channel = protectedDirectory == null
                ? FileChannel.open(target, options)
                : FileChannel.open(
                        target,
                        options,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        return Channels.newOutputStream(channel);
    }

    private synchronized File reserve(long maximum) {
        ownership.requireValid();
        if (closed || maximum < 0 || maximum > maximumBytes - reserved || files.size() >= maximumFiles) {
            throw new Capacity();
        }
        Path directory = root.resolve(UUID.randomUUID() + ".transfer");
        try {
            if (protectedDirectory == null) {
                Files.createDirectory(directory);
            } else {
                Files.createDirectory(
                        directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            }
        } catch (IOException failure) {
            throw new WorkerUnavailableException(failure);
        }
        var file = new File(directory.resolve("content"), maximum);
        files.put(file.path, file);
        reserved += maximum;
        return file;
    }

    private void privateFile(Path path) throws IOException {
        if (protectedDirectory != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private void checkFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Native transfer must remain a regular file");
        }
        if (protectedDirectory != null) {
            protectedDirectory.checkFile(path);
        }
    }

    /** Each capture owns its helper's private staging directory, including an interrupted snapshot temporary. */
    private void deleteDirectory(Path directory) throws IOException {
        if (!directory.getParent().equals(root)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !directory.toRealPath().equals(directory)) {
            throw new IOException("Native transfer directory is not contained in its store");
        }
        try (var children = Files.newDirectoryStream(directory)) {
            int count = 0;
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (++count > 2 || !(name.equals("content") || name.matches("\\.sandcrab-[0-9]+\\.snapshot"))) {
                    throw new IOException("Native transfer directory contains an unrecognized entry");
                }
                if (!Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Disposable transfer entry is not a regular file");
                }
                if (protectedDirectory != null
                        && (!Files.getOwner(child, LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(root))
                                || !Files.getAttribute(child, "unix:nlink", LinkOption.NOFOLLOW_LINKS)
                                        .equals(1))) {
                    throw new IOException("Disposable transfer entry is not privately owned");
                }
                Files.delete(child);
            }
        }
        Files.delete(directory);
    }

    synchronized void collect() {
        for (File file : List.copyOf(files.values())) {
            if (file.dropping && !file.producing) {
                file.delete();
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (File file : List.copyOf(files.values())) {
            file.close();
        }
        releaseOwnership();
    }

    private void releaseOwnership() {
        if (closed && files.isEmpty() && !released) {
            try {
                ownership.close();
                released = true;
            } catch (IOException failure) {
                throw new WorkerUnavailableException(failure);
            }
        }
    }

    final class File implements AutoCloseable {
        private final Path path;
        private long charged;
        private long size;
        private String sha256;
        private boolean producing = true;
        private boolean dropping;

        private File(Path path, long charged) {
            this.path = path;
            this.charged = charged;
        }

        Path path() {
            usable();
            return path;
        }

        long size() {
            usable();
            return size;
        }

        String sha256() {
            usable();
            return sha256;
        }

        InputStream open() throws IOException {
            usable();
            checkFile(path);
            return Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        }

        private void verify(Runnable authorize) throws IOException {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Native transfer capture must create a regular file");
            }
            privateFile(path);
            checkFile(path);
            long length = Files.size(path);
            if (length > charged) {
                throw new IOException("Native transfer exceeds its reserved bound");
            }
            var digest = SandcrabProtocol.digest();
            try (InputStream source = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
                    var input = new DigestInputStream(source, digest)) {
                byte[] buffer = new byte[65536];
                long count = 0;
                for (int read; (read = input.read(buffer)) >= 0; ) {
                    authorize.run();
                    count += read;
                    if (count > length) {
                        throw new IOException("Native transfer changed during verification");
                    }
                }
                if (count != length) {
                    throw new IOException("Native transfer ended before its declared length");
                }
            }
            authorize.run();
            synchronized (NativeTransferStore.this) {
                if (closed || dropping) {
                    throw new IOException("Native transfer was cancelled during capture");
                }
                size = length;
                sha256 = HexFormat.of().formatHex(digest.digest());
                reserved -= charged - size;
                charged = size;
            }
        }

        private void usable() {
            synchronized (NativeTransferStore.this) {
                if (closed || dropping || producing || !files.containsKey(path)) {
                    throw new WorkerUnavailableException();
                }
            }
        }

        private void abandon(Exception failure) {
            synchronized (NativeTransferStore.this) {
                dropping = true;
                producing = false;
                try {
                    delete();
                } catch (WorkerUnavailableException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
        }

        private void finishProducing() {
            synchronized (NativeTransferStore.this) {
                producing = false;
                if (dropping || closed) {
                    close();
                }
            }
        }

        @Override
        public void close() {
            synchronized (NativeTransferStore.this) {
                dropping = true;
                if (!producing) {
                    delete();
                }
            }
        }

        private void delete() {
            try {
                if (Files.exists(path.getParent(), LinkOption.NOFOLLOW_LINKS)) {
                    deleteDirectory(path.getParent());
                }
                if (files.remove(path, this)) {
                    reserved -= charged;
                }
                releaseOwnership();
            } catch (IOException failure) {
                throw new WorkerUnavailableException(failure);
            }
        }
    }

    private static final class BoundedOutput extends OutputStream {
        private final OutputStream output;
        private final long maximum;
        private final Runnable authorize;
        private long size;

        BoundedOutput(OutputStream output, long maximum, Runnable authorize) {
            this.output = output;
            this.maximum = maximum;
            this.authorize = authorize;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value});
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length > maximum - size) {
                throw new IOException("Native transfer exceeds its reserved bound");
            }
            while (length > 0) {
                authorize.run();
                int count = Math.min(length, 65536);
                output.write(bytes, offset, count);
                size += count;
                offset += count;
                length -= count;
            }
        }
    }

    @FunctionalInterface
    interface Capture {
        void write(Path path) throws IOException;
    }

    @FunctionalInterface
    interface Source {
        void write(OutputStream output) throws IOException;
    }

    static final class Capacity extends IllegalStateException {
        Capacity() {
            super("Native transfer storage has no capacity for this capture");
        }
    }
}
