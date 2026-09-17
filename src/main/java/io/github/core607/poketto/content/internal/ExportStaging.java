package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentExportException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/**
 * The export staging directory: one owner per root, proven by an exclusive lock on a protected
 * file, with every ancestor a real directory rather than a symlink. Acquiring ownership clears
 * what an earlier owner left behind, and refuses the root outright when it holds anything but
 * workspace directories of ZIP or pending files. Acquisition and release are serialized here, so
 * a caller needs no lock of its own to keep ownership consistent.
 */
final class ExportStaging {
    private final Path root;
    private FileChannel ownership;
    private FileLock lock;

    ExportStaging(Path root) {
        if (!root.isAbsolute() || !root.normalize().equals(root)) {
            throw new IllegalArgumentException("export staging must be absolute and normalized");
        }
        this.root = root;
    }

    synchronized void acquire() throws IOException {
        if (lock != null) {
            return;
        }
        createRoot();
        FileChannel channel = FileChannel.open(
                root.resolve(".owner.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        FileLock acquired = null;
        try {
            acquired = channel.tryLock();
            if (acquired == null) {
                throw failure(ContentExportException.Reason.UNAVAILABLE);
            }
            Files.setPosixFilePermissions(root.resolve(".owner.lock"), PosixFilePermissions.fromString("rw-------"));
            clearStale();
            ownership = channel;
            lock = acquired;
        } catch (IOException | RuntimeException error) {
            if (acquired != null) {
                acquired.release();
            }
            channel.close();
            throw error;
        }
    }

    // Every ancestor of the staging root is a real directory, never a symlink.
    private void createRoot() throws IOException {
        Path ancestor = root.getRoot();
        for (Path segment : root) {
            ancestor = ancestor.resolve(segment);
            if (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(ancestor);
            }
            if (!Files.isDirectory(ancestor, LinkOption.NOFOLLOW_LINKS)
                    || !ancestor.toRealPath().equals(ancestor)) {
                throw failure(ContentExportException.Reason.UNAVAILABLE);
            }
        }
        protectedDirectory(root);
    }

    // The root may hold only workspace directories of ZIP or pending files from an earlier owner; those
    // are removed, and anything else refuses ownership.
    private void clearStale() throws IOException {
        int count = 0;
        try (var directories = Files.newDirectoryStream(root)) {
            for (Path directory : directories) {
                if (directory.getFileName().toString().equals(".owner.lock")) {
                    continue;
                }
                if (++count > 128
                        || !uuid(directory.getFileName().toString())
                        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    throw failure(ContentExportException.Reason.UNAVAILABLE);
                }
                try (var files = Files.newDirectoryStream(directory)) {
                    for (Path file : files) {
                        if (++count > 256
                                || !file.getFileName().toString().matches("[0-9a-f-]{36}\\.(zip|pending)")
                                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                            throw failure(ContentExportException.Reason.UNAVAILABLE);
                        }
                        Files.delete(file);
                    }
                }
                Files.delete(directory);
            }
        }
    }

    static void protectedDirectory(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(directory);
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !directory.toRealPath().equals(directory)) {
            throw failure(ContentExportException.Reason.UNAVAILABLE);
        }
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }

    private static boolean uuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    /** Releases the lock and its channel in that order; a failed release still closes the channel. */
    synchronized void release() throws IOException {
        FileLock held = lock;
        FileChannel channel = ownership;
        lock = null;
        ownership = null;
        try {
            if (held != null) {
                held.release();
            }
        } finally {
            if (channel != null) {
                channel.close();
            }
        }
    }

    private static ContentExportException failure(ContentExportException.Reason reason) {
        return new ContentExportException(reason);
    }
}
