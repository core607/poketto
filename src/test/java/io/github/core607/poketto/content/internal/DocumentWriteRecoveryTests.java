package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.DocumentDraft;
import io.github.core607.poketto.content.DocumentWriteResult;
import io.github.core607.poketto.content.DocumentWriteService;
import io.github.core607.poketto.content.PrincipalType;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
import io.github.core607.poketto.content.WritePrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentWriteRecoveryTests {

    private static final WritePrincipal OWNER =
            new WritePrincipal(PrincipalType.ACCOUNT, "acct-7");

    @TempDir
    Path root;

    @Test
    void discardsDirtyCacheStateBeforeTheNextMachineWrite() throws Exception {
        RemoteRepositoryFixture repositories = new RemoteRepositoryFixture(root);
        WorkspaceId workspace = WorkspaceId.random();
        DocumentWriteService writes = repositories.writes(new TestClock());
        writes.create(workspace, OWNER, draft("documents/note.md"));
        Path committed = repositories.cache(workspace).resolve("documents/note.md");
        Path untracked = repositories.cache(workspace).resolve("documents/scratch.md");
        Files.writeString(committed, "local edit");
        Files.writeString(untracked, "local draft");

        DocumentWriteResult next =
                writes.create(workspace, OWNER, draft("documents/second.md"));

        assertThat(next.committed()).isTrue();
        assertThat(Files.readString(committed)).doesNotContain("local edit");
        assertThat(untracked).doesNotExist();
        assertThat(repositories.store().scan(workspace)).hasSize(2);
    }

    @Test
    void reconcilesALostSuccessfulPushResponseWithoutASecondRefUpdate() throws Exception {
        RemoteGitTransport transport = new LoseFirstPushResponse(new JGitRemoteGitTransport());
        RemoteRepositoryFixture repositories = new RemoteRepositoryFixture(root, transport);
        WorkspaceId workspace = WorkspaceId.random();

        DocumentWriteResult result = repositories.writes(new TestClock()).create(
                workspace, OWNER, draft("documents/note.md"));

        assertThat(result.committed()).isTrue();
        assertThat(repositories.remoteHead(workspace).name()).isEqualTo(result.commitId());
        assertThat(repositories.store().scan(workspace)).hasSize(1);
    }

    @Test
    void reportsAnAmbiguousWriteWhenNeitherPushNorRemoteMainCanBeVerified() {
        RemoteGitTransport transport = new UnverifiablePush(new JGitRemoteGitTransport());
        RemoteRepositoryFixture repositories = new RemoteRepositoryFixture(root, transport);
        WorkspaceId workspace = WorkspaceId.random();

        assertThatThrownBy(() -> repositories.writes(new TestClock()).create(
                        workspace, OWNER, draft("documents/note.md")))
                .isInstanceOf(RepositoryWriteAmbiguousException.class)
                .hasMessageContaining("do not retry blindly");
    }

    @Test
    void concurrentProcessesAdvancingTheSameBaseProduceOneSuccessAndOneConflict()
            throws Exception {
        CountDownLatch bothPushing = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        RemoteGitTransport firstTransport =
                new ParkPush(new JGitRemoteGitTransport(), bothPushing, release);
        RemoteGitTransport secondTransport =
                new ParkPush(new JGitRemoteGitTransport(), bothPushing, release);
        Path sharedRemotes = root.resolve("remotes");
        RemoteRepositoryFixture first =
                new RemoteRepositoryFixture(root.resolve("first-data"), sharedRemotes, firstTransport);
        RemoteRepositoryFixture second =
                new RemoteRepositoryFixture(root.resolve("second-data"), sharedRemotes, secondTransport);
        WorkspaceId workspace = WorkspaceId.random();
        first.provision(workspace);

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<DocumentWriteResult> one = threads.submit(() -> first.writes(new TestClock()).create(
                    workspace, OWNER, draft("documents/one.md")));
            Future<DocumentWriteResult> two = threads.submit(() -> second.writes(new TestClock()).create(
                    workspace, OWNER, draft("documents/two.md")));
            assertThat(bothPushing.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();

            int successes = 0;
            int conflicts = 0;
            for (Future<DocumentWriteResult> result : List.of(one, two)) {
                try {
                    assertThat(result.get(10, TimeUnit.SECONDS).committed()).isTrue();
                    successes++;
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOf(RepositoryConflictException.class);
                    conflicts++;
                }
            }
            assertThat(successes).isOne();
            assertThat(conflicts).isOne();
        } finally {
            release.countDown();
            threads.shutdownNow();
        }

        assertThat(first.store().scan(workspace)).hasSize(1);
    }

    private static DocumentDraft draft(String path) {
        return new DocumentDraft(path, "Note", List.of(), "Body text");
    }

    private static final class LoseFirstPushResponse implements RemoteGitTransport {

        private final RemoteGitTransport delegate;
        private boolean first = true;

        private LoseFirstPushResponse(RemoteGitTransport delegate) {
            this.delegate = delegate;
        }

        @Override
        public ObjectId fetchMain(Repository repository, RepositoryBinding binding) {
            return delegate.fetchMain(repository, binding);
        }

        @Override
        public PushStatus pushMain(
                Repository repository,
                RepositoryBinding binding,
                ObjectId expectedCommit,
                ObjectId candidateCommit) {
            PushStatus status =
                    delegate.pushMain(repository, binding, expectedCommit, candidateCommit);
            if (first) {
                first = false;
                throw new RemoteGitTransportException("ref update");
            }
            return status;
        }
    }

    private static final class UnverifiablePush implements RemoteGitTransport {

        private final RemoteGitTransport delegate;
        private final AtomicInteger fetches = new AtomicInteger();

        private UnverifiablePush(RemoteGitTransport delegate) {
            this.delegate = delegate;
        }

        @Override
        public ObjectId fetchMain(Repository repository, RepositoryBinding binding) {
            if (fetches.incrementAndGet() > 1) {
                throw new RemoteGitTransportException("fetch");
            }
            return delegate.fetchMain(repository, binding);
        }

        @Override
        public PushStatus pushMain(
                Repository repository,
                RepositoryBinding binding,
                ObjectId expectedCommit,
                ObjectId candidateCommit) {
            throw new RemoteGitTransportException("ref update");
        }
    }

    private static final class ParkPush implements RemoteGitTransport {

        private final RemoteGitTransport delegate;
        private final CountDownLatch entered;
        private final CountDownLatch release;

        private ParkPush(
                RemoteGitTransport delegate, CountDownLatch entered, CountDownLatch release) {
            this.delegate = delegate;
            this.entered = entered;
            this.release = release;
        }

        @Override
        public ObjectId fetchMain(Repository repository, RepositoryBinding binding) {
            return delegate.fetchMain(repository, binding);
        }

        @Override
        public PushStatus pushMain(
                Repository repository,
                RepositoryBinding binding,
                ObjectId expectedCommit,
                ObjectId candidateCommit) {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("concurrent push fixture was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return delegate.pushMain(repository, binding, expectedCommit, candidateCommit);
        }
    }
}
