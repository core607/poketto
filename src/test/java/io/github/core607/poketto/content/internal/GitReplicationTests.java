package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.core607.poketto.content.ContentRepositoryStore;
import io.github.core607.poketto.content.DocumentDraft;
import io.github.core607.poketto.content.DocumentWriteResult;
import io.github.core607.poketto.content.DocumentWriteService;
import io.github.core607.poketto.content.GitAcknowledgementMode;
import io.github.core607.poketto.content.GitReplicationException;
import io.github.core607.poketto.content.GitReplicationFailure;
import io.github.core607.poketto.content.GitReplicationStatus;
import io.github.core607.poketto.content.PrincipalType;
import io.github.core607.poketto.content.WritePrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePaths;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitReplicationTests {

    private static final WritePrincipal AGENT =
            new WritePrincipal(PrincipalType.API_KEY, "replication-test");

    @TempDir
    Path temporaryDirectory;

    private WorkspacePaths paths;
    private CanonicalDocumentCodec codec;
    private ContentRepositoryStore store;
    private TestClock clock;
    private WorkspaceId workspace;

    @BeforeEach
    void setUp() {
        paths = new WorkspacePaths(temporaryDirectory.resolve("data").toAbsolutePath());
        codec = new CanonicalDocumentCodec();
        store = new JGitContentRepositoryStore(paths, codec);
        clock = new TestClock();
        workspace = WorkspaceId.random();
    }

    @Test
    void localModeCoalescesWritesToTheLatestHeadAndRecordsVerifiedStatus() throws Exception {
        Path bare = bareRemote("local.git");
        configureRemote(workspace, bare);
        GitReplicationCoordinator replication = coordinator(GitAcknowledgementMode.LOCAL);
        DocumentWriteService writes = writes(replication);

        DocumentWriteResult first = writes.create(workspace, AGENT, draft("documents/first.md"));
        replication.replicateNow(workspace);
        DocumentWriteResult middle = writes.create(workspace, AGENT, draft("documents/middle.md"));
        DocumentWriteResult latest = writes.create(workspace, AGENT, draft("documents/latest.md"));

        assertThat(first.mirrored()).isFalse();
        assertThat(middle.mirrored()).isFalse();
        assertThat(latest.mirrored()).isFalse();
        assertThat(replication.status(workspace).lagCommits()).isEqualTo(2);

        replication.replicateNow(workspace);

        DocumentWriteResult unchanged = writes.update(
                workspace,
                AGENT,
                latest.documentId(),
                latest.revision().orElseThrow(),
                draft("documents/latest.md"));

        assertThat(remoteHead(bare)).contains(latest.commitId());
        assertThat(unchanged.committed()).isFalse();
        assertThat(unchanged.mirrored()).isTrue();
        assertThat(unchanged.commitId()).isEqualTo(latest.commitId());
        assertThat(replication.status(workspace)).satisfies(status -> {
            assertThat(status.localHead()).contains(latest.commitId());
            assertThat(status.lastMirroredCommit()).contains(latest.commitId());
            assertThat(status.lagCommits()).isZero();
            assertThat(status.lagDuration()).isEmpty();
            assertThat(status.failure()).isEmpty();
            assertThat(status.lastAttemptAt()).isPresent();
        });
    }

    @Test
    void localModeStopsWhenRemoteMainWasAdvancedOutsidePoketto() throws Exception {
        Path bare = bareRemote("diverged.git");
        configureRemote(workspace, bare);
        GitReplicationCoordinator replication = coordinator(GitAcknowledgementMode.LOCAL);
        writes(replication).create(workspace, AGENT, draft("documents/local.md"));
        replication.replicateNow(workspace);
        advanceRemoteExternally(bare);

        assertThatThrownBy(() -> replication.replicateNow(workspace))
                .isInstanceOf(GitReplicationException.class)
                .extracting(exception -> ((GitReplicationException) exception).failure())
                .isEqualTo(GitReplicationFailure.DIVERGED);
        assertThat(replication.status(workspace).failure())
                .contains(GitReplicationFailure.DIVERGED);
    }

    @Test
    void mirroredModePublishesAnUnbornCandidateBeforeAdvancingLocalMain() throws Exception {
        Path bare = bareRemote("strict.git");
        configureRemote(workspace, bare);
        GitReplicationCoordinator replication = coordinator(GitAcknowledgementMode.MIRRORED);
        replication.start(workspace);

        DocumentWriteResult result = writes(replication)
                .create(workspace, AGENT, draft("documents/strict.md"));

        assertThat(result.mirrored()).isTrue();
        assertThat(localHead(workspace)).contains(result.commitId());
        assertThat(remoteHead(bare)).contains(result.commitId());
        assertThat(replication.status(workspace).lastMirroredCommit())
                .contains(result.commitId());
    }

    @Test
    void mirroredModeLeavesLocalMainUnchangedWhenTheRemoteRejectsTheCandidate()
            throws Exception {
        Path bare = bareRemote("reject.git");
        configureRemote(workspace, bare);
        GitReplicationCoordinator seedReplication = coordinator(GitAcknowledgementMode.LOCAL);
        DocumentWriteResult seeded = writes(seedReplication)
                .create(workspace, AGENT, draft("documents/note.md"));
        seedReplication.replicateNow(workspace);

        GitRemoteMirror rejecting = new GitRemoteMirror() {
            private final GitRemoteMirror delegate =
                    new JGitRemoteMirror("origin", Duration.ofSeconds(5));

            @Override
            public Optional<ObjectId> main(Repository repository) {
                return delegate.main(repository);
            }

            @Override
            public void pushMain(
                    Repository repository,
                    ObjectId candidate,
                    Optional<ObjectId> expectedRemote) {
                throw new GitReplicationException(
                        GitReplicationFailure.NON_FAST_FORWARD,
                        false,
                        "remote main changed before the candidate was accepted");
            }
        };
        GitReplicationCoordinator strict = coordinator(GitAcknowledgementMode.MIRRORED, rejecting);

        assertThatThrownBy(() -> writes(strict).create(
                        workspace, AGENT, draft("documents/rejected.md")))
                .isInstanceOf(GitReplicationException.class)
                .hasMessageContaining("remote main changed");
        assertThat(localHead(workspace)).contains(seeded.commitId());
        assertThat(remoteHead(bare)).contains(seeded.commitId());
        assertThat(store.scan(workspace))
                .extracting(document -> document.repositoryPath())
                .containsExactly("documents/note.md");
    }

    @Test
    void mirroredStartupRecoversWhenRemoteAcceptedADescendantBeforeLocalMainAdvanced()
            throws Exception {
        Path bare = bareRemote("recovery.git");
        configureRemote(workspace, bare);
        GitReplicationCoordinator local = coordinator(GitAcknowledgementMode.LOCAL);
        DocumentWriteResult seeded = writes(local)
                .create(workspace, AGENT, draft("documents/note.md"));
        local.replicateNow(workspace);

        ObjectId accepted;
        try (Repository repository = open(workspace)) {
            ObjectId parent = ObjectId.fromString(seeded.commitId());
            accepted = descendant(repository, parent);
            new JGitRemoteMirror("origin", Duration.ofSeconds(5))
                    .pushMain(repository, accepted, Optional.of(parent));
        }
        assertThat(localHead(workspace)).contains(seeded.commitId());
        assertThat(remoteHead(bare)).contains(accepted.name());

        GitReplicationCoordinator strict = coordinator(GitAcknowledgementMode.MIRRORED);
        strict.start(workspace);

        assertThat(localHead(workspace)).contains(accepted.name());
        assertThat(strict.status(workspace).lastMirroredCommit()).contains(accepted.name());
    }

    @Test
    void mirroredStartupAllowsTwoUnbornMainsButBlocksAnAlreadyPopulatedRemote()
            throws Exception {
        Path empty = bareRemote("empty.git");
        configureRemote(workspace, empty);
        GitReplicationCoordinator strict = coordinator(GitAcknowledgementMode.MIRRORED);

        strict.start(workspace);
        assertThat(localHead(workspace)).isEmpty();

        WorkspaceId populatedWorkspace = WorkspaceId.random();
        Path populated = bareRemote("populated.git");
        populateRemote(populated);
        configureRemote(populatedWorkspace, populated);
        GitReplicationCoordinator populatedStrict = coordinator(GitAcknowledgementMode.MIRRORED);

        assertThatThrownBy(() -> populatedStrict.start(populatedWorkspace))
                .isInstanceOf(GitReplicationException.class)
                .extracting(exception -> ((GitReplicationException) exception).failure())
                .isEqualTo(GitReplicationFailure.DIVERGED);
        assertThat(localHead(populatedWorkspace)).isEmpty();
    }

    @Test
    void localModeKeepsWorkspaceFailuresAndCheckpointsIsolated() throws Exception {
        WorkspaceId other = WorkspaceId.random();
        Path firstRemote = bareRemote("first.git");
        configureRemote(workspace, firstRemote);
        store.ensureReady(other);
        GitReplicationCoordinator replication = coordinator(GitAcknowledgementMode.LOCAL);

        DocumentWriteResult first = writes(replication)
                .create(workspace, AGENT, draft("documents/first.md"));
        DocumentWriteResult second = writes(replication)
                .create(other, AGENT, draft("documents/second.md"));
        replication.replicateNow(workspace);

        assertThat(replication.status(workspace).lastMirroredCommit())
                .contains(first.commitId());
        assertThat(replication.status(other).localHead()).contains(second.commitId());
        assertThat(replication.status(other).lastMirroredCommit()).isEmpty();
        assertThatThrownBy(() -> replication.replicateNow(other))
                .isInstanceOf(GitReplicationException.class)
                .extracting(exception -> ((GitReplicationException) exception).failure())
                .isEqualTo(GitReplicationFailure.MISSING_REMOTE);
        assertThat(replication.status(other).failure())
                .contains(GitReplicationFailure.MISSING_REMOTE);
        assertThat(replication.status(workspace).failure()).isEmpty();
    }

    @Test
    void transientTimeoutRetriesAndAProcessRestartReadsThePersistedCheckpoint()
            throws Exception {
        Path bare = bareRemote("retry.git");
        configureRemote(workspace, bare);
        DocumentWriteResult local = new JGitDocumentWriteService(paths, codec, store, clock)
                .create(workspace, AGENT, draft("documents/retry.md"));
        GitRemoteMirror delegate = new JGitRemoteMirror("origin", Duration.ofSeconds(5));
        AtomicInteger attempts = new AtomicInteger();
        GitRemoteMirror flaky = new GitRemoteMirror() {
            @Override
            public Optional<ObjectId> main(Repository repository) {
                if (attempts.getAndIncrement() == 0) {
                    throw new GitReplicationException(
                            GitReplicationFailure.TIMEOUT,
                            true,
                            "remote main read reached its timeout");
                }
                return delegate.main(repository);
            }

            @Override
            public void pushMain(
                    Repository repository,
                    ObjectId candidate,
                    Optional<ObjectId> expectedRemote) {
                delegate.pushMain(repository, candidate, expectedRemote);
            }
        };
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        GitReplicationCoordinator replication = new GitReplicationCoordinator(
                paths,
                store,
                properties(GitAcknowledgementMode.LOCAL),
                flaky,
                clock,
                executor);
        try {
            replication.start(workspace);
            awaitMirrored(replication, local.commitId());
            assertThat(attempts).hasValueGreaterThanOrEqualTo(2);
        } finally {
            replication.close();
        }

        GitReplicationCoordinator restarted = coordinator(GitAcknowledgementMode.LOCAL);
        assertThat(restarted.status(workspace).lastMirroredCommit()).contains(local.commitId());
        assertThat(restarted.status(workspace).lagCommits()).isZero();
    }

    @Test
    void permanentAuthenticationFailureStopsUntilAnExplicitRetry() throws Exception {
        Path bare = bareRemote("auth.git");
        configureRemote(workspace, bare);
        new JGitDocumentWriteService(paths, codec, store, clock)
                .create(workspace, AGENT, draft("documents/auth.md"));
        AtomicInteger attempts = new AtomicInteger();
        GitRemoteMirror denied = new GitRemoteMirror() {
            @Override
            public Optional<ObjectId> main(Repository repository) {
                attempts.incrementAndGet();
                throw new GitReplicationException(
                        GitReplicationFailure.AUTHENTICATION,
                        false,
                        "remote authentication failed");
            }

            @Override
            public void pushMain(
                    Repository repository,
                    ObjectId candidate,
                    Optional<ObjectId> expectedRemote) {
                throw new AssertionError("push must not run after failed authentication");
            }
        };
        GitReplicationCoordinator replication = new GitReplicationCoordinator(
                paths,
                store,
                properties(GitAcknowledgementMode.LOCAL),
                denied,
                clock,
                Executors.newSingleThreadScheduledExecutor());
        try {
            replication.start(workspace);
            awaitFailure(replication, GitReplicationFailure.AUTHENTICATION);
            int stoppedAt = attempts.get();
            Thread.sleep(100);
            assertThat(attempts).hasValue(stoppedAt);

            replication.retry(workspace);
            awaitAttempts(attempts, stoppedAt + 1);
        } finally {
            replication.close();
        }
    }

    @Test
    void distinguishesAMissingRemoteRepositoryFromAConfiguredRemoteName() throws Exception {
        Path missing = temporaryDirectory.resolve("missing.git");
        configureRemote(workspace, missing);
        new JGitDocumentWriteService(paths, codec, store, clock)
                .create(workspace, AGENT, draft("documents/missing.md"));
        GitReplicationCoordinator replication = coordinator(GitAcknowledgementMode.LOCAL);

        assertThatThrownBy(() -> replication.replicateNow(workspace))
                .isInstanceOf(GitReplicationException.class)
                .extracting(exception -> ((GitReplicationException) exception).failure())
                .isEqualTo(GitReplicationFailure.REMOTE_REPOSITORY_MISSING);
    }

    @Test
    void explicitStrictRetrySharesTheRepositoryWriteLock() throws Exception {
        Path bare = bareRemote("lock.git");
        configureRemote(workspace, bare);
        GitReplicationCoordinator local = coordinator(GitAcknowledgementMode.LOCAL);
        writes(local).create(workspace, AGENT, draft("documents/seed.md"));
        local.replicateNow(workspace);

        CountDownLatch writeEnteredRemote = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        CountDownLatch retryEnteredRemote = new CountDownLatch(1);
        GitRemoteMirror delegate = new JGitRemoteMirror("origin", Duration.ofSeconds(5));
        java.util.concurrent.atomic.AtomicReference<Thread> writer =
                new java.util.concurrent.atomic.AtomicReference<>();
        GitRemoteMirror parked = new GitRemoteMirror() {
            @Override
            public Optional<ObjectId> main(Repository repository) {
                Thread current = Thread.currentThread();
                if (writer.compareAndSet(null, current)) {
                    writeEnteredRemote.countDown();
                    await(releaseWrite);
                } else if (writer.get() != current) {
                    retryEnteredRemote.countDown();
                }
                return delegate.main(repository);
            }

            @Override
            public void pushMain(
                    Repository repository,
                    ObjectId candidate,
                    Optional<ObjectId> expectedRemote) {
                delegate.pushMain(repository, candidate, expectedRemote);
            }
        };
        GitReplicationCoordinator strict = coordinator(GitAcknowledgementMode.MIRRORED, parked);
        var threads = Executors.newFixedThreadPool(2);
        try {
            Future<DocumentWriteResult> write = threads.submit(() -> writes(strict)
                    .create(workspace, AGENT, draft("documents/locked.md")));
            assertThat(writeEnteredRemote.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> retry = threads.submit(() -> strict.retry(workspace));

            assertThat(retryEnteredRemote.await(200, TimeUnit.MILLISECONDS)).isFalse();
            releaseWrite.countDown();
            assertThat(write.get(5, TimeUnit.SECONDS).mirrored()).isTrue();
            retry.get(5, TimeUnit.SECONDS);
            assertThat(retryEnteredRemote.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseWrite.countDown();
            threads.shutdownNow();
        }
    }

    private GitReplicationCoordinator coordinator(GitAcknowledgementMode mode) {
        return coordinator(mode, new JGitRemoteMirror("origin", Duration.ofSeconds(5)));
    }

    private GitReplicationCoordinator coordinator(
            GitAcknowledgementMode mode, GitRemoteMirror remote) {
        return new GitReplicationCoordinator(
                paths,
                store,
                properties(mode),
                remote,
                clock,
                mock(ScheduledExecutorService.class));
    }

    private static GitReplicationProperties properties(GitAcknowledgementMode mode) {
        return new GitReplicationProperties(
                mode,
                "origin",
                Duration.ofMillis(10),
                Duration.ofMillis(100),
                Duration.ofSeconds(5));
    }

    private DocumentWriteService writes(GitReplicationCoordinator replication) {
        return new JGitDocumentWriteService(
                paths,
                codec,
                store,
                replication,
                replication.repositoryLocks(),
                clock);
    }

    private void configureRemote(WorkspaceId target, Path bare) throws Exception {
        store.ensureReady(target);
        try (Repository repository = open(target)) {
            repository.getConfig().setString(
                    "remote", "origin", "url", bare.toUri().toString());
            repository.getConfig().save();
        }
    }

    private Path bareRemote(String name) throws Exception {
        Path directory = temporaryDirectory.resolve(name);
        try (Git ignored = Git.init().setBare(true).setDirectory(directory.toFile()).call()) {
            return directory;
        }
    }

    private static Optional<String> remoteHead(Path bare) throws Exception {
        try (Repository repository = new FileRepositoryBuilder()
                .setGitDir(bare.toFile())
                .setBare()
                .build()) {
            return Optional.ofNullable(repository.resolve(Constants.R_HEADS + "main"))
                    .map(ObjectId::name);
        }
    }

    private Optional<String> localHead(WorkspaceId target) throws Exception {
        try (Repository repository = open(target)) {
            return Optional.ofNullable(repository.resolve(Constants.R_HEADS + "main"))
                    .map(ObjectId::name);
        }
    }

    private Repository open(WorkspaceId target) throws IOException {
        Path directory = paths.contentDirectory(target);
        return new FileRepositoryBuilder()
                .setWorkTree(directory.toFile())
                .findGitDir(directory.toFile())
                .build();
    }

    private static ObjectId descendant(Repository repository, ObjectId parent) throws Exception {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            CommitBuilder builder = new CommitBuilder();
            builder.setTreeId(repository.resolve(parent.name() + "^{tree}"));
            builder.setParentId(parent);
            PersonIdent ident = new PersonIdent("Poketto", "poketto@invalid");
            builder.setAuthor(ident);
            builder.setCommitter(ident);
            builder.setMessage("accepted before local ref update");
            ObjectId commit = inserter.insert(builder);
            inserter.flush();
            return commit;
        }
    }

    private void populateRemote(Path bare) throws Exception {
        WorkspaceId source = WorkspaceId.random();
        configureRemote(source, bare);
        GitReplicationCoordinator replication = coordinator(GitAcknowledgementMode.LOCAL);
        writes(replication).create(source, AGENT, draft("documents/remote.md"));
        replication.replicateNow(source);
    }

    private void advanceRemoteExternally(Path bare) throws Exception {
        Path checkout = temporaryDirectory.resolve("external-checkout");
        try (Git git = Git.cloneRepository()
                .setURI(bare.toUri().toString())
                .setDirectory(checkout.toFile())
                .setBranch("main")
                .call()) {
            java.nio.file.Files.writeString(checkout.resolve("external.txt"), "outside writer");
            git.add().addFilepattern("external.txt").call();
            git.commit()
                    .setAuthor("External", "external@invalid")
                    .setCommitter("External", "external@invalid")
                    .setMessage("external update")
                    .call();
            git.push().setRemote("origin").call();
        }
    }

    private void awaitMirrored(
            GitReplicationCoordinator replication, String expectedCommit) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            GitReplicationStatus status = replication.status(workspace);
            if (status.lastMirroredCommit().filter(expectedCommit::equals).isPresent()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("replication did not reach the expected commit");
    }

    private void awaitFailure(
            GitReplicationCoordinator replication, GitReplicationFailure expected)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (replication.status(workspace).failure().filter(expected::equals).isPresent()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("replication did not report " + expected);
    }

    private static void awaitAttempts(AtomicInteger attempts, int expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (attempts.get() >= expected) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("replication did not perform the expected retry");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("parked replication was not released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("parked replication was interrupted", exception);
        }
    }

    private static DocumentDraft draft(String path) {
        return new DocumentDraft(path, "Replication", List.of(), "Durable body");
    }
}
