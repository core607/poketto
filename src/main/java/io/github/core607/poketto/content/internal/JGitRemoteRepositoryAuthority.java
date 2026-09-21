package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;

final class JGitRemoteRepositoryAuthority implements RepositoryAuthority {

    private static final Duration MAX_PROTECTION = Duration.ofMinutes(5);

    private final WorkspacePaths paths;
    private final RepositoryBindingSource bindings;
    private final RemoteGitTransport transport;
    private final RepositoryCaches caches;
    private final Clock clock;
    private final Map<WorkspaceId, CacheLock> workspaceLocks = new HashMap<>();
    private final ReentrantLock cacheLifecycleLock = new ReentrantLock();
    private final ScopedValue<PreparedBinding> prepared = ScopedValue.newInstance();

    JGitRemoteRepositoryAuthority(
            WorkspacePaths paths,
            RepositoryBindingSource bindings,
            RemoteGitTransport transport,
            int maxCachedWorkspaces,
            Clock clock) {
        this.paths = Objects.requireNonNull(paths, "workspace paths must not be null");
        this.bindings = Objects.requireNonNull(bindings, "binding source must not be null");
        this.transport = Objects.requireNonNull(transport, "remote transport must not be null");
        this.clock = Objects.requireNonNull(clock, "repository clock must not be null");
        this.caches = new RepositoryCaches(paths, maxCachedWorkspaces, this::isIdle);
    }

    @Override
    public void ensureReady(WorkspaceId workspaceId) {
        read(workspaceId, snapshot -> null);
    }

    @Override
    public <T> T withPreparedCredentials(WorkspaceId workspace, Supplier<T> action) {
        Objects.requireNonNull(workspace, "workspace is required");
        Objects.requireNonNull(action, "credential-scoped action is required");
        if (prepared.isBound() && prepared.get().workspace().equals(workspace)) {
            prepared.get().binding().requireCurrent();
            return action.get();
        }
        RepositoryBinding binding =
                Objects.requireNonNull(bindings.bindingFor(workspace), "repository binding is required");
        binding.requireCurrent();
        return ScopedValue.where(prepared, new PreparedBinding(workspace, binding))
                .call(action::get);
    }

    private RepositoryBinding binding(WorkspaceId workspace) {
        if (prepared.isBound() && prepared.get().workspace().equals(workspace)) {
            return prepared.get().binding();
        }
        return Objects.requireNonNull(bindings.bindingFor(workspace), "repository binding is required");
    }

    private record PreparedBinding(WorkspaceId workspace, RepositoryBinding binding) {}

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
                if (!Files.isDirectory(cache) || RepositoryCaches.isEmpty(cache)) {
                    throw failure(workspaceId, "no repository cache exists");
                }
                opened = RepositoryCaches.openOrInitialize(cache, workspaceId);
            } catch (IOException exception) {
                throw failure(workspaceId, "repository cache cannot be opened");
            } finally {
                cacheLifecycleLock.unlock();
            }
            try (Repository repository = opened) {
                ObjectId commit = repository.resolve(RepositoryCaches.MAIN);
                RepositoryCaches.touch(cache);
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
        return readImmutableObjects(workspaceId, action, null);
    }

    @Override
    public void protectImmutableObjects(
            WorkspaceId workspaceId, Instant expiresAt, ObjectReaderAction<Void> validation) {
        Objects.requireNonNull(expiresAt, "source protection expiry must not be null");
        validateProtection(expiresAt);
        readImmutableObjects(workspaceId, validation, expiresAt);
    }

    private <T> T readImmutableObjects(WorkspaceId workspaceId, ObjectReaderAction<T> action, Instant protectedUntil) {
        Objects.requireNonNull(workspaceId, "workspace id must not be null");
        Objects.requireNonNull(action, "object reader action must not be null");
        CacheLock workspaceLock = acquireWorkspaceLock(workspaceId);
        try {
            Repository opened;
            Path cache = paths.contentDirectory(workspaceId);
            cacheLifecycleLock.lock();
            try {
                if (!Files.isDirectory(cache) || RepositoryCaches.isEmpty(cache)) {
                    throw failure(workspaceId, "no repository cache exists");
                }
                opened = RepositoryCaches.openExisting(cache, workspaceId);
                try {
                    RepositoryCaches.touch(cache);
                } catch (RuntimeException | Error failure) {
                    try {
                        opened.close();
                    } catch (RuntimeException | Error closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                    throw failure;
                }
            } finally {
                cacheLifecycleLock.unlock();
            }
            // Explicit objects are append-only, independent of network work or ref/worktree changes.
            // The users pin prevents eviction until both repository and reader handles have closed.
            try (Repository repository = opened;
                    ObjectReader objects = repository.newObjectReader()) {
                T result = action.read(objects);
                if (protectedUntil != null) {
                    synchronized (workspaceLocks) {
                        validateProtection(protectedUntil);
                        if (protectedUntil.isAfter(workspaceLock.protectedUntil)) {
                            workspaceLock.protectedUntil = protectedUntil;
                        }
                    }
                }
                return result;
            }
        } catch (IOException exception) {
            throw new ContentRepositoryException("immutable repository objects cannot be read", exception);
        } finally {
            releaseWorkspaceLock(workspaceId, workspaceLock);
        }
    }

    private void validateProtection(Instant expiresAt) {
        Instant now = clock.instant();
        if (!now.isBefore(expiresAt) || expiresAt.isAfter(now.plus(MAX_PROTECTION))) {
            throw new ContentRepositoryException("repository source protection must expire within five minutes");
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
                        RepositoryCaches.updateObjectRef(repository, candidate);
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
            RepositoryBinding binding = binding(workspaceId);
            binding.requireCurrent();
            Path cache = paths.contentDirectory(workspaceId);
            Repository opened;
            cacheLifecycleLock.lock();
            try {
                caches.ensureCapacity(workspaceId, cache);
                opened = RepositoryCaches.openOrInitialize(cache, workspaceId);
            } finally {
                cacheLifecycleLock.unlock();
            }
            ObjectId commit;
            try {
                commit = transport.fetchMain(opened, binding);
                if (commit.equals(ObjectId.zeroId())) {
                    // Readers of already selected immutable objects may still be active. Removing
                    // main closes the old authority binding without destroying their object store.
                    RepositoryCaches.updateObjectRef(opened, commit);
                    RepositoryCaches.reset(opened, commit);
                } else if (materialize) {
                    RepositoryCaches.reset(opened, commit);
                } else {
                    RepositoryCaches.updateObjectRef(opened, commit);
                }
            } catch (RuntimeException exception) {
                opened.close();
                throw exception;
            }
            try (Repository repository = opened) {
                RepositoryCaches.touch(cache);
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
        binding.requireCurrent();
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
            RepositoryCaches.restore(repository, remoteCommit, materialize);
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
                RepositoryCaches.restore(repository, candidateCommit, materialize);
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
            RepositoryCaches.restore(repository, remoteCommit, materialize);
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
            RepositoryCaches.restore(repository, commit, materialize);
        } catch (ContentRepositoryException ignored) {
            // A competing owner's invalid tree must neither expand into the cache nor hide the
            // already established conflict. A later operation retries cache materialization.
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
            return lock == null || (lock.users == 0 && !clock.instant().isBefore(lock.protectedUntil));
        }
    }

    private CacheLock acquireWorkspaceLock(WorkspaceId workspaceId) {
        CacheLock entry;
        synchronized (workspaceLocks) {
            Instant now = clock.instant();
            workspaceLocks.values().removeIf(lock -> lock.users == 0 && !now.isBefore(lock.protectedUntil));
            entry = workspaceLocks.computeIfAbsent(workspaceId, ignored -> new CacheLock());
            entry.users++;
        }
        return entry;
    }

    private void releaseWorkspaceLock(WorkspaceId workspaceId, CacheLock entry) {
        synchronized (workspaceLocks) {
            entry.users--;
            if (entry.users == 0 && !clock.instant().isBefore(entry.protectedUntil)) {
                workspaceLocks.remove(workspaceId, entry);
            }
        }
    }

    private static ContentRepositoryException failure(WorkspaceId workspaceId, String detail) {
        return RepositoryCaches.failure(workspaceId, detail);
    }

    @FunctionalInterface
    private interface CacheAction<T> {

        T apply(Repository repository, RepositoryBinding binding, ObjectId commit);
    }

    private static final class CacheLock {

        private final ReentrantLock lock = new ReentrantLock();
        private int users;
        private Instant protectedUntil = Instant.MIN;
    }
}
