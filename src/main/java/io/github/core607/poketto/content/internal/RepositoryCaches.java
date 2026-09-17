package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * The authority's on-disk caches: one Git worktree per workspace below the workspace paths, opened
 * or initialized under the workspace mutex, reset or ref-updated to the authoritative commit, and
 * bounded in number by evicting the least recently used idle cache. Whether a cache is idle is the
 * authority's knowledge, supplied as a predicate.
 */
final class RepositoryCaches {
    static final String MAIN = Constants.R_HEADS + "main";

    private final WorkspacePaths paths;
    private final int maxCachedWorkspaces;
    private final Predicate<Path> idle;

    RepositoryCaches(WorkspacePaths paths, int maxCachedWorkspaces, Predicate<Path> idle) {
        if (maxCachedWorkspaces < 1) {
            throw new IllegalArgumentException("repository cache must allow at least one workspace");
        }
        this.paths = paths;
        this.maxCachedWorkspaces = maxCachedWorkspaces;
        this.idle = idle;
    }

    static Repository openOrInitialize(Path cache, WorkspaceId workspaceId) {
        try {
            if (Files.notExists(cache)) {
                Files.createDirectories(cache);
                return Git.init()
                        .setDirectory(cache.toFile())
                        .setInitialBranch("main")
                        .call()
                        .getRepository();
            }
            if (!Files.isDirectory(cache)) {
                throw failure(workspaceId, "repository cache path is not a directory");
            }
            if (isEmpty(cache)) {
                return Git.init()
                        .setDirectory(cache.toFile())
                        .setInitialBranch("main")
                        .call()
                        .getRepository();
            }
            Repository repository = openExisting(cache, workspaceId);
            try {
                RefUpdate.Result head = repository.updateRef(Constants.HEAD).link(MAIN);
                if (!(head == RefUpdate.Result.NEW
                        || head == RefUpdate.Result.NO_CHANGE
                        || head == RefUpdate.Result.FORCED)) {
                    throw failure(workspaceId, "repository cache HEAD cannot be attached to main");
                }
                return repository;
            } catch (IOException | RuntimeException exception) {
                repository.close();
                throw exception;
            }
        } catch (ContentRepositoryException exception) {
            throw exception;
        } catch (IOException | GitAPIException exception) {
            throw failure(workspaceId, "repository cache cannot be opened");
        }
    }

    static Repository openExisting(Path cache, WorkspaceId workspaceId) throws IOException {
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        builder.findGitDir(cache.toFile());
        if (builder.getGitDir() == null) {
            throw failure(workspaceId, "repository cache is not a Git worktree");
        }
        Repository repository = builder.build();
        try {
            if (repository.isBare()
                    || !repository
                            .getWorkTree()
                            .toPath()
                            .toAbsolutePath()
                            .normalize()
                            .equals(cache.toAbsolutePath().normalize())) {
                throw failure(workspaceId, "repository cache is not the expected Git worktree");
            }
            return repository;
        } catch (RuntimeException exception) {
            repository.close();
            throw exception;
        }
    }

    static void reset(Repository repository, ObjectId commit) {
        ManagedDocumentBounds.check(repository, commit);
        try {
            if (commit.equals(ObjectId.zeroId())) {
                // No commit exists to reset --hard to, and clean skips staged files, so a write
                // interrupted before its root commit would leak its residue into the next
                // candidate. Empty the index first; clean then removes the leftover files.
                clearIndex(repository);
                Git git = Git.wrap(repository);
                git.clean()
                        .setCleanDirectories(true)
                        .setForce(true)
                        .setIgnore(false)
                        .call();
                ContentWorktree.clearIntent(repository);
                return;
            }
            RefUpdate update = repository.updateRef(MAIN);
            update.setNewObjectId(commit);
            update.setForceUpdate(true);
            RefUpdate.Result result = update.forceUpdate();
            if (!(result == RefUpdate.Result.NEW
                    || result == RefUpdate.Result.FORCED
                    || result == RefUpdate.Result.FAST_FORWARD
                    || result == RefUpdate.Result.NO_CHANGE)) {
                throw new ContentRepositoryException("repository cache main cannot be updated");
            }
            Git git = Git.wrap(repository);
            git.reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef(commit.name())
                    .call();
            git.clean()
                    .setCleanDirectories(true)
                    .setForce(true)
                    .setIgnore(false)
                    .call();
            ContentWorktree.clearIntent(repository);
        } catch (IOException | GitAPIException exception) {
            throw new ContentRepositoryException("repository cache cannot be materialized");
        }
    }

    static void restore(Repository repository, ObjectId commit, boolean materialize) {
        if (materialize) {
            reset(repository, commit);
        } else {
            updateObjectRef(repository, commit);
        }
    }

    static void updateObjectRef(Repository repository, ObjectId commit) {
        try {
            RefUpdate update = repository.updateRef(MAIN);
            if (commit.equals(ObjectId.zeroId())) {
                ObjectId previous = repository.resolve(MAIN);
                if (previous == null) {
                    return;
                }
                // JGit rejects deletion of the checked-out branch. Detach HEAD only while the
                // workspace mutex is held, then restore the symbolic unborn HEAD without pruning.
                RefUpdate detached = repository.updateRef(Constants.HEAD, true);
                detached.setNewObjectId(previous);
                requireRefChange(detached.forceUpdate(), "HEAD detach");
                Throwable deletionFailure = null;
                try {
                    update.setForceUpdate(true);
                    requireRefChange(update.delete(), "main delete");
                } catch (IOException | RuntimeException | Error failure) {
                    deletionFailure = failure;
                    throw failure;
                } finally {
                    try {
                        requireRefChange(repository.updateRef(Constants.HEAD).link(MAIN), "HEAD relink");
                    } catch (IOException | RuntimeException | Error relinkFailure) {
                        if (deletionFailure == null) {
                            throw relinkFailure;
                        }
                        deletionFailure.addSuppressed(relinkFailure);
                    }
                }
                return;
            }
            update.setNewObjectId(commit);
            update.setForceUpdate(true);
            RefUpdate.Result result = update.forceUpdate();
            if (!(result == RefUpdate.Result.NEW
                    || result == RefUpdate.Result.FORCED
                    || result == RefUpdate.Result.FAST_FORWARD
                    || result == RefUpdate.Result.NO_CHANGE)) {
                throw new ContentRepositoryException("repository object cache main cannot be updated");
            }
        } catch (IOException exception) {
            throw new ContentRepositoryException("repository object cache main cannot be updated", exception);
        }
    }

    private static void requireRefChange(RefUpdate.Result result, String operation) {
        if (!(result == RefUpdate.Result.FORCED
                || result == RefUpdate.Result.NO_CHANGE
                || result == RefUpdate.Result.NEW
                || result == RefUpdate.Result.FAST_FORWARD)) {
            throw new ContentRepositoryException("repository object cache " + operation + " failed: " + result);
        }
    }

    static void clearIndex(Repository repository) throws IOException {
        DirCache index = repository.lockDirCache();
        try {
            index.clear();
            index.write();
            if (!index.commit()) {
                throw new ContentRepositoryException("repository cache index cannot be cleared");
            }
        } finally {
            index.unlock();
        }
    }

    void ensureCapacity(WorkspaceId current, Path currentCache) {
        List<Path> caches = existing();
        int allowedExisting = Files.isDirectory(currentCache) ? maxCachedWorkspaces : maxCachedWorkspaces - 1;
        while (caches.size() > allowedExisting) {
            Path victim = caches.stream()
                    .filter(path -> !path.equals(currentCache))
                    .filter(idle)
                    .min(Comparator.comparing(RepositoryCaches::lastModified))
                    .orElseThrow(() -> failure(
                            current, "repository cache capacity is occupied by active or protected workspaces"));
            deleteTree(victim);
            caches.remove(victim);
        }
    }

    private List<Path> existing() {
        Path root = paths.workspacesDirectory();
        if (!Files.isDirectory(root)) {
            return new ArrayList<>();
        }
        try (var workspaceDirectories = Files.list(root)) {
            // Foreign directories are not caches this authority may evict, so counting them
            // toward the bound would let them permanently exhaust the cache capacity.
            return new ArrayList<>(workspaceDirectories
                    .filter(RepositoryCaches::isWorkspaceDirectory)
                    .map(path -> path.resolve("content"))
                    .filter(Files::isDirectory)
                    .toList());
        } catch (IOException exception) {
            throw new ContentRepositoryException("repository cache inventory cannot be read");
        }
    }

    private static boolean isWorkspaceDirectory(Path workspaceDirectory) {
        try {
            WorkspaceId.parse(workspaceDirectory.getFileName().toString());
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException exception) {
            return FileTime.fromMillis(0);
        }
    }

    static void touch(Path cache) {
        try {
            Files.setLastModifiedTime(cache, FileTime.from(Instant.now()));
        } catch (IOException exception) {
            throw new ContentRepositoryException("repository cache access time cannot be recorded", exception);
        }
    }

    static boolean isEmpty(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    static void deleteTree(Path root) {
        if (Files.notExists(root)) {
            return;
        }
        try {
            LocalFileTrees.delete(root);
        } catch (IOException exception) {
            throw new ContentRepositoryException("repository cache cannot be evicted");
        }
    }

    static ContentRepositoryException failure(WorkspaceId workspaceId, String detail) {
        return new ContentRepositoryException("workspace " + workspaceId + " repository authority: " + detail);
    }
}
