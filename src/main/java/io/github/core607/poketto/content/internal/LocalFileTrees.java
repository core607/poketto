package io.github.core607.poketto.content.internal;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;

/** Local directories this module creates and later removes: the repository cache and export staging. */
final class LocalFileTrees {

    private LocalFileTrees() {}

    /**
     * Deletes a directory and everything below it. JGit marks loose objects read-only and Windows
     * refuses to delete a read-only file, so the flag is cleared first -- on the entry itself, never
     * along a link, so a symlink inside the tree is unlinked and whatever it points at is left
     * alone. The caller decides what a failure means and translates it.
     */
    static void delete(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isRegularFile()) {
                    var dos = Files.getFileAttributeView(file, DosFileAttributeView.class, NOFOLLOW_LINKS);
                    if (dos != null) {
                        dos.setReadOnly(false);
                    }
                }
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
