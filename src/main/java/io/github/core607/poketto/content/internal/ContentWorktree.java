package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Worktree and index mechanics for one machine write, including the intent journal that bounds
 * crash recovery to the paths a write was already replacing.
 */
final class ContentWorktree {

    private static final String INTENT_JOURNAL = "poketto-write-intent";
    private static final String MAIN_TREE = Constants.R_HEADS + "main^{tree}";

    private ContentWorktree() {}

    /**
     * Records the paths a write is about to replace. Recovery resets exactly these paths, so a
     * write must journal every path it touches before touching any of them.
     */
    static void recordIntent(Repository repository, Set<String> paths) {
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("a write intent must name at least one path");
        }
        String content = String.join("\n", new TreeSet<>(paths)) + "\n";
        try {
            Files.writeString(
                    intentJournal(repository),
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.SYNC);
        } catch (IOException exception) {
            throw new ContentRepositoryException("write intent journal cannot be recorded", exception);
        }
    }

    /**
     * Resets every journaled path to {@code HEAD} and removes the journal. Does nothing when no
     * journal exists, so a dirty worktree without one remains untouched operator activity.
     *
     * @return true when a journal was found and rolled back
     */
    static boolean rollback(Repository repository) {
        Optional<Set<String>> journaled = readIntent(repository);
        if (journaled.isEmpty()) {
            return false;
        }
        restoreFromHead(repository, journaled.get());
        clearIntent(repository);
        return true;
    }

    static void clearIntent(Repository repository) {
        try {
            Files.deleteIfExists(intentJournal(repository));
        } catch (IOException exception) {
            throw new ContentRepositoryException("write intent journal cannot be removed", exception);
        }
    }

    /**
     * Writes the given documents and removes the given paths from both the index and the worktree.
     */
    static void apply(Repository repository, Map<String, byte[]> upserts, Set<String> deletions) {
        try {
            stage(repository, upserts, deletions);
        } catch (IOException exception) {
            throw new ContentRepositoryException("repository index and worktree cannot be updated", exception);
        }
    }

    private static void restoreFromHead(Repository repository, Set<String> paths) {
        Map<String, byte[]> upserts = new LinkedHashMap<>();
        Set<String> deletions = new LinkedHashSet<>();
        try {
            ObjectId tree = repository.resolve(MAIN_TREE);
            for (String path : paths) {
                Optional<byte[]> committed = tree == null ? Optional.empty() : blobAt(repository, tree, path);
                committed.ifPresentOrElse(bytes -> upserts.put(path, bytes), () -> deletions.add(path));
            }
            stage(repository, upserts, deletions);
        } catch (IOException exception) {
            throw new ContentRepositoryException("journaled paths cannot be restored from main", exception);
        }
    }

    private static Optional<byte[]> blobAt(Repository repository, ObjectId tree, String path) throws IOException {
        try (TreeWalk walk = TreeWalk.forPath(repository, path, tree)) {
            if (walk == null || !FileMode.REGULAR_FILE.equals(walk.getFileMode(0))) {
                return Optional.empty();
            }
            return Optional.of(
                    repository.open(walk.getObjectId(0), Constants.OBJ_BLOB).getBytes());
        }
    }

    private static void stage(Repository repository, Map<String, byte[]> upserts, Set<String> deletions)
            throws IOException {
        Map<String, ObjectId> blobs = new LinkedHashMap<>();
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            for (Map.Entry<String, byte[]> upsert : upserts.entrySet()) {
                blobs.put(upsert.getKey(), inserter.insert(Constants.OBJ_BLOB, upsert.getValue()));
            }
            inserter.flush();
        }

        Map<String, Instant> writtenAt = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> upsert : upserts.entrySet()) {
            writtenAt.put(upsert.getKey(), writeWorkTreeFile(repository, upsert.getKey(), upsert.getValue()));
        }
        for (String path : deletions) {
            deleteWorkTreeFile(repository, path);
        }

        DirCache cache = repository.lockDirCache();
        try {
            DirCacheEditor editor = cache.editor();
            upserts.forEach((path, bytes) -> editor.add(new DirCacheEditor.PathEdit(path) {
                @Override
                public void apply(DirCacheEntry entry) {
                    entry.setFileMode(FileMode.REGULAR_FILE);
                    entry.setObjectId(blobs.get(path));
                    entry.setLength(bytes.length);
                    entry.setLastModified(writtenAt.get(path));
                }
            }));
            deletions.forEach(path -> editor.add(new DirCacheEditor.DeletePath(path)));
            editor.finish();
            cache.write();
            if (!cache.commit()) {
                throw new ContentRepositoryException("repository index cannot be updated");
            }
        } finally {
            cache.unlock();
        }
    }

    private static Instant writeWorkTreeFile(Repository repository, String path, byte[] bytes) throws IOException {
        Path file = repository.getWorkTree().toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        return Files.getLastModifiedTime(file).toInstant();
    }

    private static void deleteWorkTreeFile(Repository repository, String path) throws IOException {
        Path workTree = repository.getWorkTree().toPath();
        Path file = workTree.resolve(path);
        if (file.getParent() == null || !Files.isDirectory(file.getParent())) {
            // A path below something that is not a directory holds no file of ours to remove.
            return;
        }
        Files.deleteIfExists(file);
        // Git tracks no empty directory, so one left behind would outlive its document.
        for (Path parent = file.getParent();
                parent != null && !parent.equals(workTree) && parent.startsWith(workTree);
                parent = parent.getParent()) {
            if (!Files.isDirectory(parent)) {
                return;
            }
            try (var children = Files.list(parent)) {
                if (children.findAny().isPresent()) {
                    return;
                }
            }
            Files.deleteIfExists(parent);
        }
    }

    private static Optional<Set<String>> readIntent(Repository repository) {
        Path journal = intentJournal(repository);
        if (Files.notExists(journal)) {
            return Optional.empty();
        }
        try {
            Set<String> paths = new LinkedHashSet<>(Files.readAllLines(journal, StandardCharsets.UTF_8));
            paths.removeIf(String::isBlank);
            return Optional.of(paths);
        } catch (IOException exception) {
            throw new ContentRepositoryException("write intent journal cannot be read", exception);
        }
    }

    private static Path intentJournal(Repository repository) {
        return repository.getDirectory().toPath().resolve(INTENT_JOURNAL);
    }
}
