package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryMediaValidator;
import io.github.core607.poketto.content.RepositoryMoveRequest;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.content.RepositoryWriteAttempt;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryWriteCheckpointTests {
    @TempDir
    Path directory;

    private final WorkspaceId workspace = WorkspaceId.random();
    private final AuthPrincipal principal = mock(AuthPrincipal.class);
    private final AtomicReference<RepositoryWriteAttempt> retained = new AtomicReference<>();
    private final CountingTransport transport = new CountingTransport(retained);

    @Test
    void failedPatchCheckpointDoesNotAdvanceRemote() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, transport);
        var base = fixture.commitRemote(workspace, Map.of("old.md", bytes("old")));
        var patch = creation(base);
        var failure = new IllegalStateException("checkpoint disk full");

        assertThatThrownBy(() -> service(fixture).apply(principal, workspace, patch, attempt -> {
                    throw failure;
                }))
                .isSameAs(failure);

        assertThat(transport.pushes).isZero();
        assertThat(fixture.remoteHead(workspace)).isEqualTo(base);
    }

    @Test
    void retainedPatchCandidateCanBeRecoveredAfterInterruptionBeforePush() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, transport);
        var base = fixture.commitRemote(workspace, Map.of("old.md", bytes("old")));
        var patch = creation(base);
        assertThatThrownBy(() -> service(fixture).apply(principal, workspace, patch, attempt -> {
                    retained.set(attempt);
                    throw new IllegalStateException("process interrupted after checkpoint");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(fixture.remoteHead(workspace)).isEqualTo(base);

        var result = service(fixture).recover(principal, workspace, patch, retained.get(), retained::set);

        assertThat(result.commit()).isEqualTo(retained.get().commit());
        assertThat(transport.pushes).isEqualTo(1);
        assertCandidateMatchesRemote(fixture);
    }

    @Test
    void alreadyCommittedPatchReconcilesWithoutAnotherCheckpointOrPush() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, transport);
        var base = fixture.commitRemote(workspace, Map.of("old.md", bytes("old")));
        var patch = creation(base);
        var result = service(fixture).apply(principal, workspace, patch, retained::set);

        var recovered = service(fixture).recover(principal, workspace, patch, retained.get(), attempt -> {
            throw new AssertionError("already committed write must not checkpoint or push again");
        });

        assertThat(recovered.commit()).isEqualTo(result.commit());
        assertThat(transport.pushes).isEqualTo(1);
        assertCandidateMatchesRemote(fixture);
    }

    @Test
    void failedMoveCheckpointDoesNotAdvanceRemote() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, transport);
        var base = fixture.commitRemote(workspace, Map.of("source.md", bytes("source")));
        var move = new RepositoryMoveRequest(base.name(), "source.md", "destination.md");
        var failure = new IllegalStateException("checkpoint disk full");

        assertThatThrownBy(() -> service(fixture).move(principal, workspace, move, attempt -> {
                    throw failure;
                }))
                .isSameAs(failure);

        assertThat(transport.pushes).isZero();
        assertThat(fixture.remoteHead(workspace)).isEqualTo(base);
    }

    @Test
    void retainedMoveUsesTheSameCommitWhenRetriedAndThenReconciled() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, transport);
        var base = fixture.commitRemote(workspace, Map.of("source.md", bytes("source")));
        var move = new RepositoryMoveRequest(base.name(), "source.md", "destination.md");
        assertThatThrownBy(() -> service(fixture).move(principal, workspace, move, attempt -> {
                    retained.set(attempt);
                    throw new IllegalStateException("process interrupted after checkpoint");
                }))
                .isInstanceOf(IllegalStateException.class);

        var result = service(fixture).recover(principal, workspace, move, retained.get(), retained::set);
        var recovered = service(fixture).recover(principal, workspace, move, retained.get(), attempt -> {
            throw new AssertionError("already committed move must not checkpoint or push again");
        });

        assertThat(recovered.commit()).isEqualTo(result.commit());
        assertThat(transport.pushes).isEqualTo(1);
        assertCandidateMatchesRemote(fixture);
    }

    @Test
    void unchangedContentDoesNotRequireCheckpointOrRemoteWrite() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, transport);
        var base = fixture.commitRemote(workspace, Map.of("source.md", bytes("source")));
        var patch = new RepositoryPatch(
                Optional.of(base.name()),
                List.of(new RepositoryTextChange(
                        "source.md",
                        false,
                        Optional.of(DocumentRevision.sha256(bytes("source"))),
                        Optional.of("source"))));

        var result = service(fixture).apply(principal, workspace, patch, attempt -> {
            throw new AssertionError("unchanged content must not checkpoint");
        });

        assertThat(result.committed()).isFalse();
        assertThat(transport.pushes).isZero();
        assertThat(fixture.remoteHead(workspace)).isEqualTo(base);
    }

    private JGitRepositoryPatchService service(RemoteRepositoryFixture fixture) {
        var auth = mock(AuthService.class);
        when(principal.kind()).thenReturn(AuthPrincipal.Kind.ACCOUNT);
        when(principal.subjectId()).thenReturn(UUID.fromString("bf562fc1-f15b-4adb-80d2-b0fdab1a568d"));
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        return new JGitRepositoryPatchService(
                fixture.authority(),
                auth,
                Clock.systemUTC(),
                (id, snapshot) -> {},
                (id, snapshot) -> {},
                mock(RepositoryMediaValidator.class));
    }

    private void assertCandidateMatchesRemote(RemoteRepositoryFixture fixture) throws Exception {
        assertThat(fixture.remoteHead(workspace).name())
                .isEqualTo(retained.get().commit());
        try (Repository remote = fixture.openRemote(workspace)) {
            assertThat(remote.open(ObjectId.fromString(retained.get().commit())).getBytes())
                    .containsExactly(retained.get().object());
        }
    }

    private static RepositoryPatch creation(ObjectId base) {
        return new RepositoryPatch(
                Optional.of(base.name()),
                List.of(new RepositoryTextChange("new.md", true, Optional.empty(), Optional.of("new bytes"))));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class CountingTransport implements RemoteGitTransport {
        private final RemoteGitTransport delegate = new JGitRemoteGitTransport();
        private final AtomicReference<RepositoryWriteAttempt> retained;
        private int pushes;

        private CountingTransport(AtomicReference<RepositoryWriteAttempt> retained) {
            this.retained = retained;
        }

        @Override
        public ObjectId fetchMain(Repository repository, RepositoryBinding binding) {
            return delegate.fetchMain(repository, binding);
        }

        @Override
        public PushStatus pushMain(
                Repository repository, RepositoryBinding binding, ObjectId expectedCommit, ObjectId candidateCommit) {
            assertThat(retained.get()).isNotNull();
            assertThat(retained.get().commit()).isEqualTo(candidateCommit.name());
            try (var formatter = new ObjectInserter.Formatter()) {
                assertThat(formatter.idFor(Constants.OBJ_COMMIT, retained.get().object()))
                        .isEqualTo(candidateCommit);
            }
            pushes++;
            return delegate.pushMain(repository, binding, expectedCommit, candidateCommit);
        }
    }
}
