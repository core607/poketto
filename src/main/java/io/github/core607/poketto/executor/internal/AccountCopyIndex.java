package io.github.core607.poketto.executor.internal;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/** Serializes copy-owner admission and removes only unreferenced files while their owner lock is held. */
record AccountCopyIndex(
        Path root,
        RetainedDirectory directory,
        AccountFileBaselines baselines,
        AccountCopyFiles plans,
        AccountCopyFiles uppers,
        int maximumOwners) {
    RetainedFileLocks.Held lock() throws IOException {
        directory.checkRoot();
        Path lock = root.resolve(".index.lock");
        return RetainedFileLocks.await(lock, () -> directory.openLock(lock), Duration.ofSeconds(5));
    }

    int countOwners() throws IOException {
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
                    if (++count > maximumOwners) {
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
            cleanPending(name.substring(0, 64));
            baselines.retainOnly(name.substring(0, 64), null);
            plans.retainOnly(name.substring(0, 64), null, Set.of());
            uppers.retainOnly(name.substring(0, 64), null, Set.of());
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

    /** Only the owner lock permits deleting this owner's unpublished journal or baseline. */
    void cleanPending(String ownerKey) throws IOException {
        Path path = root.resolve(ownerKey + ".pending");
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            directory.checkFile(path);
            Files.delete(path);
        }
    }
}
