package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePaths;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

final class JGitRemoteRepositoryAuthority implements RepositoryAuthority {

    private static final String MAIN = Constants.R_HEADS + "main";

    private final WorkspacePaths paths;
    private final RepositoryBindingSource bindings;
    private final RemoteGitTransport transport;
    private final int maxCachedWorkspaces;
    private final Map<WorkspaceId, CacheLock> workspaceLocks = new java.util.HashMap<>();
    private final ReentrantLock cacheLifecycleLock = new ReentrantLock();

    JGitRemoteRepositoryAuthority(
            WorkspacePaths paths,
            RepositoryBindingSource bindings,
            RemoteGitTransport transport,
            int maxCachedWorkspaces) {
        this.paths = Objects.requireNonNull(paths, "workspace paths must not be null");
        this.bindings = Objects.requireNonNull(bindings, "binding source must not be null");
        this.transport = Objects.requireNonNull(transport, "remote transport must not be null");
        if (maxCachedWorkspaces < 1) {
            throw new IllegalArgumentException("repository cache must allow at least one workspace");
        }
        this.maxCachedWorkspaces = maxCachedWorkspaces;
    }

    @Override
    public void ensureReady(WorkspaceId workspaceId) {
        read(workspaceId, snapshot -> null);
    }

    @Override
    public <T> T read(WorkspaceId workspaceId, SnapshotReader<T> reader) {
        Objects.requireNonNull(reader, "snapshot reader must not be null");
        return inCache(workspaceId, (repository, binding, commit) -> reader.read(snapshot(repository, commit)));
    }

    @Override
    public <T> T readObjects(WorkspaceId workspaceId, SnapshotReader<T> reader) {
        Objects.requireNonNull(reader, "snapshot reader must not be null");
        return inCache(workspaceId, false, (repository, binding, commit) -> reader.read(snapshot(repository, commit)));
    }

    @Override
    public <T> T readCache(WorkspaceId workspaceId, SnapshotReader<T> reader) {
        Objects.requireNonNull(reader, "snapshot reader must not be null");
        Objects.requireNonNull(workspaceId, "workspace id must not be null");
        CacheLock workspaceLock = acquireWorkspaceLock(workspaceId);
        workspaceLock.lock.lock();
        try {
            Path cache = paths.contentDirectory(workspaceId);
            Repository opened;
            cacheLifecycleLock.lock();
            try {
                // An offline read serves what an earlier fetch left behind and never creates a
                // cache, so an unbound or never-fetched workspace fails without a footprint.
                if (!Files.isDirectory(cache) || isEmpty(cache)) {
                    throw failure(workspaceId, "no repository cache exists");
                }
                opened = openOrInitialize(cache, workspaceId);
            } catch (IOException exception) {
                throw failure(workspaceId, "repository cache cannot be opened");
            } finally {
                cacheLifecycleLock.unlock();
            }
            try (Repository repository = opened) {
                ObjectId commit = repository.resolve(MAIN);
                touch(cache);
                return reader.read(snapshot(repository, commit == null ? ObjectId.zeroId() : commit));
            } catch (IOException exception) {
                throw failure(workspaceId, "repository cache main cannot be resolved");
            }
        } finally {
            workspaceLock.lock.unlock();
            releaseWorkspaceLock(workspaceId, workspaceLock);
        }
    }

    @Override
    public <T> T readImmutableObjects(WorkspaceId workspaceId, ObjectReaderAction<T> action) {
        Objects.requireNonNull(workspaceId, "workspace id must not be null");
        Objects.requireNonNull(action, "object reader action must not be null");
        CacheLock workspaceLock = acquireWorkspaceLock(workspaceId);
        try {
            Repository opened;
            workspaceLock.lock.lock();
            try {
                Path cache = paths.contentDirectory(workspaceId);
                cacheLifecycleLock.lock();
                try {
                    if (!Files.isDirectory(cache) || isEmpty(cache))
                        throw failure(workspaceId, "no repository cache exists");
                    touch(cache);
                    opened = openExistingCache(cache, workspaceId);
                } finally {
                    cacheLifecycleLock.unlock();
                }
            } finally {
                workspaceLock.lock.unlock();
            }
            // The users reference survives mutex release. Eviction must observe the repository
            // and its object reader as active until both handles have actually closed.
            try (Repository repository = opened;
                    ObjectReader objects = repository.newObjectReader()) {
                return action.read(objects);
            }
        } catch (IOException exception) {
            throw new ContentRepositoryException("immutable repository objects cannot be read", exception);
        } finally {
            releaseWorkspaceLock(workspaceId, workspaceLock);
        }
    }

    @Override
    public <T> T write(WorkspaceId workspaceId, CandidateWriter<T> writer) {
        Objects.requireNonNull(writer, "candidate writer must not be null");
        return inCache(
                workspaceId,
                (repository, binding, baseCommit) -> writer.write(
                        snapshot(repository, baseCommit),
                        candidateCommit -> advance(
                                workspaceId, repository, binding, baseCommit, parseCommit(candidateCommit), true)));
    }

    @Override
    public <T> T writeObjects(WorkspaceId workspaceId, CandidateWriter<T> writer) {
        Objects.requireNonNull(writer, "candidate writer must not be null");
        return inCache(
                workspaceId,
                false,
                (repository, binding, baseCommit) -> writer.write(snapshot(repository, baseCommit), candidateCommit -> {
                    ObjectId candidate = parseCommit(candidateCommit);
                    advance(workspaceId, repository, binding, baseCommit, candidate, false);
                    try {
                        updateObjectRef(repository, candidate);
                    } catch (ContentRepositoryException exception) {
                        throw new RepositoryWriteAmbiguousException(
                                "remote acknowledged the patch but cache recording failed; read remote main before retrying");
                    }
                }));
    }

    private <T> T inCache(WorkspaceId workspaceId, CacheAction<T> action) {
        return inCache(workspaceId, true, action);
    }

    private <T> T inCache(WorkspaceId workspaceId, boolean materialize, CacheAction<T> action) {
        Objects.requireNonNull(workspaceId, "workspace id must not be null");
        CacheLock workspaceLock = acquireWorkspaceLock(workspaceId);
        workspaceLock.lock.lock();
        try {
            RepositoryBinding binding =
                    Objects.requireNonNull(bindings.bindingFor(workspaceId), "repository binding must not be null");
            Path cache = paths.contentDirectory(workspaceId);
            Repository opened;
            cacheLifecycleLock.lock();
            try {
                ensureCacheCapacity(workspaceId, cache);
                opened = openOrInitialize(cache, workspaceId);
            } finally {
                cacheLifecycleLock.unlock();
            }
            ObjectId commit;
            try {
                commit = transport.fetchMain(opened, binding);
                if (commit.equals(ObjectId.zeroId())) {
                    // Readers of already selected immutable objects may still be active. Removing
                    // main closes the old authority binding without destroying their object store.
                    updateObjectRef(opened, commit);
                    resetCache(opened, commit);
                } else if (materialize) {
                    resetCache(opened, commit);
                } else {
                    updateObjectRef(opened, commit);
                }
            } catch (RuntimeException exception) {
                opened.close();
                throw exception;
            }
            try (Repository repository = opened) {
                touch(cache);
                return action.apply(repository, binding, commit);
            }
        } catch (RemoteGitTransportException exception) {
            throw failure(workspaceId, exception.getMessage());
        } finally {
            workspaceLock.lock.unlock();
            releaseWorkspaceLock(workspaceId, workspaceLock);
        }
    }

    private void advance(
            WorkspaceId workspaceId,
            Repository repository,
            RepositoryBinding binding,
            ObjectId baseCommit,
            ObjectId candidateCommit,
            boolean materialize) {
        Objects.requireNonNull(candidateCommit, "candidate commit must not be null");
        try {
            RemoteGitTransport.PushStatus result = transport.pushMain(repository, binding, baseCommit, candidateCommit);
            if (result == RemoteGitTransport.PushStatus.CONFLICT) {
                restoreAfterConflict(repository, binding, materialize);
                throw new RepositoryConflictException(
                        "workspace " + workspaceId + " remote main changed while the write was being prepared");
            }
        } catch (RemoteGitRejectedException rejected) {
            reconcileRejection(workspaceId, repository, binding, baseCommit, rejected, materialize);
        } catch (RemoteGitTransportException lostResponse) {
            reconcileLostResponse(workspaceId, repository, binding, baseCommit, candidateCommit, materialize);
        }
    }

    /**
     * A refusal is definite: the candidate did not land. The remote refuses both for a competing
     * advance it could not classify as non-fast-forward (a ref lock held by another writer) and
     * for a policy such as permissions or branch protection; only remote {@code main} tells
     * them apart, and an unreadable remote still leaves the outcome definite.
     */
    private void reconcileRejection(
            WorkspaceId workspaceId,
            Repository repository,
            RepositoryBinding binding,
            ObjectId baseCommit,
            RemoteGitRejectedException rejected,
            boolean materialize) {
        final ObjectId remoteCommit;
        try {
            remoteCommit = transport.fetchMain(repository, binding);
        } catch (RemoteGitTransportException unreadable) {
            throw failure(workspaceId, rejected.getMessage() + "; main did not advance");
        }
        if (!remoteCommit.equals(baseCommit)) {
            resetAfterConflict(repository, remoteCommit, materialize);
            throw new RepositoryConflictException(
                    "workspace " + workspaceId + " remote main changed while the write was being prepared");
        }
        if (!remoteCommit.equals(ObjectId.zeroId())) {
            restoreCache(repository, remoteCommit, materialize);
        }
        throw failure(workspaceId, rejected.getMessage() + "; main did not advance");
    }

    private static Snapshot snapshot(Repository repository, ObjectId commit) {
        Optional<String> commitId = commit.equals(ObjectId.zeroId()) ? Optional.empty() : Optional.of(commit.name());
        return new Snapshot(repository.getWorkTree().toPath(), commitId);
    }

    private static ObjectId parseCommit(String candidateCommit) {
        Objects.requireNonNull(candidateCommit, "candidate commit must not be null");
        try {
            return ObjectId.fromString(candidateCommit);
        } catch (IllegalArgumentException exception) {
            throw new ContentRepositoryException("candidate commit is not a Git object id");
        }
    }

    private void reconcileLostResponse(
            WorkspaceId workspaceId,
            Repository repository,
            RepositoryBinding binding,
            ObjectId baseCommit,
            ObjectId candidateCommit,
            boolean materialize) {
        final ObjectId remoteCommit;
        try {
            remoteCommit = transport.fetchMain(repository, binding);
        } catch (RemoteGitTransportException unreadable) {
            throw new RepositoryWriteAmbiguousException("workspace " + workspaceId
                    + " remote write response was lost and main cannot be verified; do not retry blindly");
        }
        if (remoteCommit.equals(candidateCommit)) {
            try {
                restoreCache(repository, candidateCommit, materialize);
            } catch (ContentRepositoryException exception) {
                throw new RepositoryWriteAmbiguousException(
                        "remote acknowledged the patch but cache recording failed; read remote main before retrying");
            }
            return;
        }
        if (!remoteCommit.equals(baseCommit)) {
            resetAfterConflict(repository, remoteCommit, materialize);
            throw new RepositoryConflictException(
                    "workspace " + workspaceId + " remote main changed while the write was being prepared");
        }
        if (!remoteCommit.equals(ObjectId.zeroId())) {
            restoreCache(repository, remoteCommit, materialize);
        }
        throw failure(workspaceId, "remote write failed before main advanced");
    }

    private void restoreAfterConflict(Repository repository, RepositoryBinding binding, boolean materialize) {
        try {
            resetAfterConflict(repository, transport.fetchMain(repository, binding), materialize);
        } catch (RemoteGitTransportException ignored) {
            // The outcome is already definite. A later operation rebuilds the disposable cache.
        }
    }

    private static void resetAfterConflict(Repository repository, ObjectId commit, boolean materialize) {
        try {
            restoreCache(repository, commit, materialize);
        } catch (ContentRepositoryException ignored) {
            // A competing owner's invalid tree must neither expand into the cache nor hide the
            // already established conflict. A later operation retries cache materialization.
        }
    }

    private Repository openOrInitialize(Path cache, WorkspaceId workspaceId) {
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
            Repository repository = openExistingCache(cache, workspaceId);
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

    private static Repository openExistingCache(Path cache, WorkspaceId workspaceId) throws IOException {
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        builder.findGitDir(cache.toFile());
        if (builder.getGitDir() == null) throw failure(workspaceId, "repository cache is not a Git worktree");
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

    private static void resetCache(Repository repository, ObjectId commit) {
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

    private static void restoreCache(Repository repository, ObjectId commit, boolean materialize) {
        if (materialize) {
            resetCache(repository, commit);
        } else {
            updateObjectRef(repository, commit);
        }
    }

    private static void updateObjectRef(Repository repository, ObjectId commit) {
        try {
            RefUpdate update = repository.updateRef(MAIN);
            if (commit.equals(ObjectId.zeroId())) {
                ObjectId previous = repository.resolve(MAIN);
                if (previous == null) return;
                // JGit rejects deletion of the checked-out branch. Detach HEAD only while the
                // workspace mutex is held, then restore the symbolic unborn HEAD without pruning.
                RefUpdate detached = repository.updateRef(Constants.HEAD, true);
                detached.setNewObjectId(previous);
                requireRefChange(detached.forceUpdate());
                try {
                    update.setForceUpdate(true);
                    requireRefChange(update.delete());
                } finally {
                    requireRefChange(repository.updateRef(Constants.HEAD).link(MAIN));
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

    private static void requireRefChange(RefUpdate.Result result) {
        if (!(result == RefUpdate.Result.FORCED
                || result == RefUpdate.Result.NO_CHANGE
                || result == RefUpdate.Result.NEW
                || result == RefUpdate.Result.FAST_FORWARD)) {
            throw new ContentRepositoryException("repository object cache main cannot be removed");
        }
    }

    private static void clearIndex(Repository repository) throws IOException {
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

    private void ensureCacheCapacity(WorkspaceId current, Path currentCache) {
        List<Path> caches = existingCaches();
        int allowedExisting = Files.isDirectory(currentCache) ? maxCachedWorkspaces : maxCachedWorkspaces - 1;
        while (caches.size() > allowedExisting) {
            Path victim = caches.stream()
                    .filter(path -> !path.equals(currentCache))
                    .filter(this::isIdle)
                    .min(Comparator.comparing(JGitRemoteRepositoryAuthority::lastModified))
                    .orElseThrow(() -> failure(current, "repository cache capacity is occupied by active workspaces"));
            deleteTree(victim);
            caches.remove(victim);
        }
    }

    private List<Path> existingCaches() {
        Path root = paths.workspacesDirectory();
        if (!Files.isDirectory(root)) {
            return new java.util.ArrayList<>();
        }
        try (var workspaceDirectories = Files.list(root)) {
            // Foreign directories are not caches this authority may evict, so counting them
            // toward the bound would let them permanently exhaust the cache capacity.
            return new java.util.ArrayList<>(workspaceDirectories
                    .filter(JGitRemoteRepositoryAuthority::isWorkspaceDirectory)
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

    private boolean isIdle(Path cache) {
        Path workspaceDirectory = cache.getParent();
        if (workspaceDirectory == null) {
            return false;
        }
        WorkspaceId id = WorkspaceId.parse(workspaceDirectory.getFileName().toString());
        synchronized (workspaceLocks) {
            CacheLock lock = workspaceLocks.get(id);
            return lock == null || lock.users == 0;
        }
    }

    private CacheLock acquireWorkspaceLock(WorkspaceId workspaceId) {
        CacheLock entry;
        synchronized (workspaceLocks) {
            entry = workspaceLocks.computeIfAbsent(workspaceId, ignored -> new CacheLock());
            entry.users++;
        }
        return entry;
    }

    private void releaseWorkspaceLock(WorkspaceId workspaceId, CacheLock entry) {
        synchronized (workspaceLocks) {
            entry.users--;
            if (entry.users == 0) {
                workspaceLocks.remove(workspaceId, entry);
            }
        }
    }

    private static FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException exception) {
            return FileTime.fromMillis(0);
        }
    }

    private static void touch(Path cache) {
        try {
            Files.setLastModifiedTime(cache, FileTime.from(Instant.now()));
        } catch (IOException exception) {
            throw new ContentRepositoryException("repository cache access time cannot be recorded");
        }
    }

    private static boolean isEmpty(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    private static void deleteTree(Path root) {
        if (Files.notExists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    clearReadOnly(file);
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
        } catch (IOException exception) {
            throw new ContentRepositoryException("repository cache cannot be evicted");
        }
    }

    private static void clearReadOnly(Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("dos")) {
            Files.setAttribute(path, "dos:readonly", false);
        }
    }

    private static ContentRepositoryException failure(WorkspaceId workspaceId, String detail) {
        return new ContentRepositoryException("workspace " + workspaceId + " repository authority: " + detail);
    }

    @FunctionalInterface
    private interface CacheAction<T> {

        T apply(Repository repository, RepositoryBinding binding, ObjectId commit);
    }

    private static final class CacheLock {

        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }
}
