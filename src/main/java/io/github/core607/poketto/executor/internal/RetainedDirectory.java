package io.github.core607.poketto.executor.internal;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Common Linux ownership and inode checks for private metadata and immutable baseline stores. */
final class RetainedDirectory {
    private final Path root;
    private final int uid;

    RetainedDirectory(Path root) throws IOException {
        this.root = root;
        uid = (Integer) Files.getAttribute(Path.of("/proc/self"), "unix:uid");
        checkAncestors(root.getParent());
        try {
            Files.createDirectory(
                    root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            sync(root.getParent());
        } catch (FileAlreadyExistsException existing) {
            // Validate an existing root before opening any entry or lock.
        }
        checkRoot();
    }

    void checkRoot() throws IOException {
        checkAncestors(root);
        var attributes = Files.readAttributes(root, PosixFileAttributes.class, NOFOLLOW_LINKS);
        if (!ownedWithMode(root, attributes, "rwx------")) {
            throw new IOException("retention root must be private and owned by the application");
        }
    }

    void checkFile(Path file) throws IOException {
        var attributes = Files.readAttributes(file, PosixFileAttributes.class, NOFOLLOW_LINKS);
        if (!privateUnaliasedFile(file, attributes)) {
            throw new IOException("retention entry must be a private unaliased application-owned file");
        }
    }

    FileChannel openLock(Path path) throws IOException {
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
            } catch (IOException closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
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

    static void sync(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, READ, NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }
}
