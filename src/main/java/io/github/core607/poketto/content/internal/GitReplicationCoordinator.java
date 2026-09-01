package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.ContentRepositoryStore;
import io.github.core607.poketto.content.GitAcknowledgementMode;
import io.github.core607.poketto.content.GitReplicationException;
import io.github.core607.poketto.content.GitReplicationFailure;
import io.github.core607.poketto.content.GitReplicationService;
import io.github.core607.poketto.content.GitReplicationStatus;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePaths;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

final class GitReplicationCoordinator
        implements GitReplicationService, GitWriteDurability, AutoCloseable {

    private static final String MAIN = Constants.R_HEADS + "main";
    private static final String MIRRORED_MAIN = "refs/poketto/mirrored/main";

    private final WorkspacePaths paths;
    private final ContentRepositoryStore store;
    private final GitReplicationProperties properties;
    private final GitRemoteMirror remote;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    private final ContentRepositoryLocks repositoryLocks;
    private final ConcurrentMap<WorkspaceId, WorkerState> workers = new ConcurrentHashMap<>();

    GitReplicationCoordinator(
            WorkspacePaths paths,
            ContentRepositoryStore store,
            GitReplicationProperties properties,
            Clock clock) {
        this(
                paths,
                store,
                properties,
                new JGitRemoteMirror(properties.remote(), properties.networkTimeout()),
                clock,
                Executors.newScheduledThreadPool(2, runnable -> {
                    Thread thread = new Thread(runnable, "poketto-git-replication");
                    thread.setDaemon(true);
                    return thread;
                }),
                new ContentRepositoryLocks());
    }

    GitReplicationCoordinator(
            WorkspacePaths paths,
            ContentRepositoryStore store,
            GitReplicationProperties properties,
            GitRemoteMirror remote,
            Clock clock,
            ScheduledExecutorService executor) {
        this(paths, store, properties, remote, clock, executor, new ContentRepositoryLocks());
    }

    GitReplicationCoordinator(
            WorkspacePaths paths,
            ContentRepositoryStore store,
            GitReplicationProperties properties,
            GitRemoteMirror remote,
            Clock clock,
            ScheduledExecutorService executor,
            ContentRepositoryLocks repositoryLocks) {
        this.paths = Objects.requireNonNull(paths, "workspace paths must not be null");
        this.store = Objects.requireNonNull(store, "repository store must not be null");
        this.properties = Objects.requireNonNull(properties, "replication properties must not be null");
        this.remote = Objects.requireNonNull(remote, "remote mirror must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.repositoryLocks =
                Objects.requireNonNull(repositoryLocks, "repository locks must not be null");
    }

    ContentRepositoryLocks repositoryLocks() {
        return repositoryLocks;
    }

    void start(WorkspaceId workspaceId) {
        store.ensureReady(workspaceId);
        if (properties.acknowledgement() == GitAcknowledgementMode.MIRRORED) {
            Lock lock = repositoryLocks.forWorkspace(workspaceId);
            lock.lock();
            try (Repository repository = open(workspaceId)) {
                reconcileMirrored(workspaceId, repository);
            } finally {
                lock.unlock();
            }
        } else {
            wake(workspaceId, true);
        }
    }

    @Override
    public void beforeWrite(WorkspaceId workspaceId, Repository repository) {
        if (properties.acknowledgement() == GitAcknowledgementMode.MIRRORED) {
            reconcileMirrored(workspaceId, repository);
        }
    }

    @Override
    public GitCommitOutcome commit(
            WorkspaceId workspaceId,
            Repository repository,
            PersonIdent author,
            String message) {
        if (properties.acknowledgement() == GitAcknowledgementMode.LOCAL) {
            ObjectId commit = localCommit(repository, author, message);
            wake(workspaceId, false);
            return new GitCommitOutcome(commit, false);
        }

        Optional<ObjectId> local = resolve(repository, MAIN);
        ObjectId candidate = insertCommit(repository, local, author, message);
        recordAttempt(workspaceId);
        try {
            remote.pushMain(repository, candidate, local);
            requireRemote(repository, candidate);
            advanceLocal(repository, local, candidate);
            checkpoint(repository, candidate);
            clearFailure(workspaceId);
            return new GitCommitOutcome(candidate, true);
        } catch (GitReplicationException exception) {
            recordFailure(workspaceId, exception.failure());
            throw exception;
        }
    }

    @Override
    public boolean isMirrored(Repository repository, ObjectId commit) {
        return resolve(repository, MIRRORED_MAIN).filter(commit::equals).isPresent();
    }

    @Override
    public GitReplicationStatus status(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspace id must not be null");
        store.ensureReady(workspaceId);
        try (Repository repository = open(workspaceId)) {
            boolean configured = remote.configured(repository);
            Optional<ObjectId> local = resolve(repository, MAIN);
            Optional<ObjectId> mirrored = resolve(repository, MIRRORED_MAIN);
            Lag lag = configured
                    ? lag(repository, local, mirrored)
                    : new Lag(0, Optional.empty());
            WorkerState worker = workers.get(workspaceId);
            WorkerSnapshot snapshot = worker == null
                    ? new WorkerSnapshot(null, null)
                    : snapshot(worker);
            return new GitReplicationStatus(
                    configured,
                    local.map(ObjectId::name),
                    mirrored.map(ObjectId::name),
                    lag.commits(),
                    lag.duration(),
                    Optional.ofNullable(snapshot.lastAttemptAt()),
                    Optional.ofNullable(snapshot.failure()));
        }
    }

    @Override
    public void retry(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspace id must not be null");
        WorkerState state = workers.computeIfAbsent(workspaceId, ignored -> new WorkerState());
        synchronized (state) {
            state.halted = false;
            state.failure = null;
            state.retryAttempt = 0;
        }
        if (properties.acknowledgement() == GitAcknowledgementMode.MIRRORED) {
            start(workspaceId);
        } else {
            wake(workspaceId, true);
        }
    }

    /** Runs one local-mode attempt synchronously for filesystem integration tests. */
    void replicateNow(WorkspaceId workspaceId) {
        replicateOnce(workspaceId);
    }

    private void reconcileMirrored(WorkspaceId workspaceId, Repository repository) {
        recordAttempt(workspaceId);
        try {
            Optional<ObjectId> local = resolve(repository, MAIN);
            Optional<ObjectId> remoteMain = remote.main(repository);
            if (local.isEmpty() && remoteMain.isEmpty()) {
                clearFailure(workspaceId);
                return;
            }
            if (local.isEmpty()) {
                throw diverged("remote main has commits while local main is unborn");
            }
            if (remoteMain.isEmpty()) {
                throw diverged("remote main has no common starting commit");
            }
            if (local.get().equals(remoteMain.get())) {
                checkpoint(repository, local.get());
                clearFailure(workspaceId);
                return;
            }
            if (isAncestor(repository, remoteMain.get(), local.get())) {
                remote.pushMain(repository, local.get(), remoteMain);
                requireRemote(repository, local.get());
                checkpoint(repository, local.get());
                clearFailure(workspaceId);
                return;
            }
            if (isAncestor(repository, local.get(), remoteMain.get())) {
                advanceFromRemote(repository, local.get(), remoteMain.get());
                checkpoint(repository, remoteMain.get());
                clearFailure(workspaceId);
                return;
            }
            throw diverged("local and remote main have diverged");
        } catch (GitReplicationException exception) {
            recordFailure(workspaceId, exception.failure());
            throw exception;
        }
    }

    private void replicateOnce(WorkspaceId workspaceId) {
        try (Repository repository = open(workspaceId)) {
            if (!remote.configured(repository)) {
                // No remote means replication is off for this workspace, not failing.
                clearFailure(workspaceId);
                return;
            }
            recordAttempt(workspaceId);
            Optional<ObjectId> local = resolve(repository, MAIN);
            Optional<ObjectId> remoteMain = remote.main(repository);
            if (local.isEmpty()) {
                if (remoteMain.isPresent()) {
                    throw diverged("remote main has commits while local main is unborn");
                }
                clearFailure(workspaceId);
                return;
            }
            if (remoteMain.isPresent() && !remoteMain.get().equals(local.get())) {
                if (!isAncestor(repository, remoteMain.get(), local.get())) {
                    throw diverged("remote main is ahead of or diverged from local main");
                }
            }
            if (remoteMain.filter(local.get()::equals).isEmpty()) {
                remote.pushMain(repository, local.get(), remoteMain);
            }
            requireRemote(repository, local.get());
            checkpoint(repository, local.get());
            clearFailure(workspaceId);
        } catch (GitReplicationException exception) {
            recordFailure(workspaceId, exception.failure());
            throw exception;
        }
    }

    private void wake(WorkspaceId workspaceId, boolean explicit) {
        WorkerState state = workers.computeIfAbsent(workspaceId, ignored -> new WorkerState());
        synchronized (state) {
            if (state.scheduled || (!explicit && state.halted)) {
                return;
            }
            state.scheduled = true;
        }
        try {
            executor.execute(() -> runWorker(workspaceId, state));
        } catch (RejectedExecutionException exception) {
            synchronized (state) {
                state.scheduled = false;
                state.failure = GitReplicationFailure.UNKNOWN;
            }
        }
    }

    private void runWorker(WorkspaceId workspaceId, WorkerState state) {
        synchronized (state) {
            state.scheduled = false;
            if (state.running) {
                state.rerun = true;
                return;
            }
            state.running = true;
        }
        Duration retryDelay = null;
        try {
            replicateOnce(workspaceId);
            synchronized (state) {
                state.retryAttempt = 0;
                state.halted = false;
            }
        } catch (GitReplicationException exception) {
            synchronized (state) {
                if (exception.transientFailure()) {
                    retryDelay = retryDelay(state.retryAttempt++);
                } else {
                    state.halted = true;
                }
            }
        } catch (RuntimeException exception) {
            recordFailure(workspaceId, GitReplicationFailure.UNKNOWN);
            synchronized (state) {
                state.halted = true;
            }
        } finally {
            boolean rerun;
            synchronized (state) {
                state.running = false;
                rerun = state.rerun;
                state.rerun = false;
            }
            if (retryDelay != null) {
                schedule(workspaceId, state, retryDelay);
            } else if (rerun || needsReplication(workspaceId)) {
                wake(workspaceId, false);
            }
        }
    }

    private void schedule(WorkspaceId workspaceId, WorkerState state, Duration delay) {
        synchronized (state) {
            if (state.scheduled) {
                return;
            }
            state.scheduled = true;
        }
        try {
            executor.schedule(
                    () -> runWorker(workspaceId, state), delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
            synchronized (state) {
                state.scheduled = false;
                state.failure = GitReplicationFailure.UNKNOWN;
            }
        }
    }

    private boolean needsReplication(WorkspaceId workspaceId) {
        WorkerState state = workers.get(workspaceId);
        if (state != null) {
            synchronized (state) {
                if (state.halted) {
                    return false;
                }
            }
        }
        try (Repository repository = open(workspaceId)) {
            return remote.configured(repository)
                    && !resolve(repository, MAIN).equals(resolve(repository, MIRRORED_MAIN));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private Duration retryDelay(int attempt) {
        long multiplier = 1L << Math.min(attempt, 30);
        Duration candidate;
        try {
            candidate = properties.initialRetry().multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            return properties.maxRetry();
        }
        return candidate.compareTo(properties.maxRetry()) > 0 ? properties.maxRetry() : candidate;
    }

    private void requireRemote(Repository repository, ObjectId expected) {
        if (!remote.main(repository).filter(expected::equals).isPresent()) {
            throw new GitReplicationException(
                    GitReplicationFailure.NON_FAST_FORWARD,
                    false,
                    "remote main did not retain the accepted commit");
        }
    }

    private static ObjectId localCommit(
            Repository repository, PersonIdent author, String message) {
        try {
            return Git.wrap(repository).commit()
                    .setAuthor(author)
                    .setCommitter(author)
                    .setSign(false)
                    .setMessage(message)
                    .call()
                    .getId();
        } catch (GitAPIException exception) {
            throw new ContentRepositoryException("document cannot be committed", exception);
        }
    }

    private static ObjectId insertCommit(
            Repository repository,
            Optional<ObjectId> parent,
            PersonIdent author,
            String message) {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            DirCache index = repository.readDirCache();
            ObjectId tree = index.writeTree(inserter);
            CommitBuilder builder = new CommitBuilder();
            builder.setTreeId(tree);
            parent.ifPresent(builder::setParentId);
            builder.setAuthor(author);
            builder.setCommitter(author);
            builder.setMessage(message);
            ObjectId commit = inserter.insert(builder);
            inserter.flush();
            return commit;
        } catch (IOException exception) {
            throw new ContentRepositoryException("candidate commit cannot be created", exception);
        }
    }

    private static void advanceLocal(
            Repository repository, Optional<ObjectId> expected, ObjectId candidate) {
        try {
            RefUpdate update = repository.updateRef(MAIN);
            update.setExpectedOldObjectId(expected.orElse(ObjectId.zeroId()));
            update.setNewObjectId(candidate);
            update.setRefLogMessage("poketto mirrored write", false);
            RefUpdate.Result result = update.update();
            if (!(result == RefUpdate.Result.NEW
                    || result == RefUpdate.Result.FAST_FORWARD
                    || result == RefUpdate.Result.NO_CHANGE)) {
                throw new GitReplicationException(
                        GitReplicationFailure.NON_FAST_FORWARD,
                        false,
                        "remote accepted the commit but local main could not advance");
            }
        } catch (IOException exception) {
            throw new ContentRepositoryException(
                    "remote accepted the commit but local main could not advance", exception);
        }
    }

    private static void advanceFromRemote(
            Repository repository, ObjectId expected, ObjectId remoteMain) {
        Optional<String> dirty = ContentWorktree.describeUncleanState(repository);
        boolean recoveringWrite = ContentWorktree.hasIntent(repository);
        if (dirty.isPresent() && !recoveringWrite) {
            throw new GitReplicationException(
                    GitReplicationFailure.DIVERGED,
                    false,
                    "local main is behind remote main but the worktree has operator changes");
        }
        advanceLocal(repository, Optional.of(expected), remoteMain);
        if (recoveringWrite) {
            ContentWorktree.rollback(repository);
            return;
        }
        try {
            Git.wrap(repository).reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef(remoteMain.name())
                    .call();
        } catch (GitAPIException exception) {
            throw new ContentRepositoryException(
                    "worktree cannot follow recovered remote main", exception);
        }
    }

    private static void checkpoint(Repository repository, ObjectId commit) {
        try {
            RefUpdate update = repository.updateRef(MIRRORED_MAIN);
            update.setNewObjectId(commit);
            update.setForceUpdate(true);
            update.setRefLogMessage("poketto verified remote main", false);
            RefUpdate.Result result = update.update();
            if (!(result == RefUpdate.Result.NEW
                    || result == RefUpdate.Result.FAST_FORWARD
                    || result == RefUpdate.Result.FORCED
                    || result == RefUpdate.Result.NO_CHANGE)) {
                throw new ContentRepositoryException(
                        "verified remote checkpoint cannot advance: " + result);
            }
        } catch (IOException exception) {
            throw new ContentRepositoryException(
                    "verified remote checkpoint cannot be recorded", exception);
        }
    }

    private static Optional<ObjectId> resolve(Repository repository, String ref) {
        try {
            return Optional.ofNullable(repository.resolve(ref));
        } catch (IOException exception) {
            throw new ContentRepositoryException("Git ref cannot be resolved: " + ref, exception);
        }
    }

    private static boolean isAncestor(
            Repository repository, ObjectId possibleAncestor, ObjectId commit) {
        try (ObjectReader reader = repository.newObjectReader();
                RevWalk walk = new RevWalk(reader)) {
            if (!reader.has(possibleAncestor) || !reader.has(commit)) {
                return false;
            }
            return walk.isMergedInto(walk.parseCommit(possibleAncestor), walk.parseCommit(commit));
        } catch (IOException exception) {
            throw new ContentRepositoryException("Git commit relationship cannot be read", exception);
        }
    }

    private Lag lag(
            Repository repository, Optional<ObjectId> local, Optional<ObjectId> mirrored) {
        if (local.isEmpty() || local.equals(mirrored)) {
            return new Lag(0, Optional.empty());
        }
        try (RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(local.orElseThrow()));
            if (mirrored.isPresent() && isAncestor(repository, mirrored.get(), local.get())) {
                walk.markUninteresting(walk.parseCommit(mirrored.get()));
            }
            int commits = 0;
            Instant oldest = null;
            for (RevCommit commit : walk) {
                commits++;
                Instant committedAt = commit.getCommitterIdent().getWhenAsInstant();
                if (oldest == null || committedAt.isBefore(oldest)) {
                    oldest = committedAt;
                }
            }
            Duration duration = oldest == null
                    ? Duration.ZERO
                    : Duration.between(oldest, clock.instant()).isNegative()
                            ? Duration.ZERO
                            : Duration.between(oldest, clock.instant());
            return new Lag(commits, commits == 0 ? Optional.empty() : Optional.of(duration));
        } catch (IOException exception) {
            throw new ContentRepositoryException("replication lag cannot be calculated", exception);
        }
    }

    private Repository open(WorkspaceId workspaceId) {
        return JGitContentRepositoryStore.openExisting(
                paths.contentDirectory(workspaceId), workspaceId);
    }

    private void recordAttempt(WorkspaceId workspaceId) {
        WorkerState state = workers.computeIfAbsent(workspaceId, ignored -> new WorkerState());
        synchronized (state) {
            state.lastAttemptAt = clock.instant();
        }
    }

    private void recordFailure(WorkspaceId workspaceId, GitReplicationFailure failure) {
        WorkerState state = workers.computeIfAbsent(workspaceId, ignored -> new WorkerState());
        synchronized (state) {
            state.failure = failure;
        }
    }

    private void clearFailure(WorkspaceId workspaceId) {
        WorkerState state = workers.computeIfAbsent(workspaceId, ignored -> new WorkerState());
        synchronized (state) {
            state.failure = null;
        }
    }

    private static WorkerSnapshot snapshot(WorkerState state) {
        synchronized (state) {
            return new WorkerSnapshot(state.lastAttemptAt, state.failure);
        }
    }

    private static GitReplicationException diverged(String message) {
        return new GitReplicationException(
                GitReplicationFailure.DIVERGED, false, message);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private record Lag(int commits, Optional<Duration> duration) {
    }

    private record WorkerSnapshot(Instant lastAttemptAt, GitReplicationFailure failure) {
    }

    private static final class WorkerState {
        private boolean running;
        private boolean scheduled;
        private boolean rerun;
        private boolean halted;
        private int retryAttempt;
        private Instant lastAttemptAt;
        private GitReplicationFailure failure;
    }
}
