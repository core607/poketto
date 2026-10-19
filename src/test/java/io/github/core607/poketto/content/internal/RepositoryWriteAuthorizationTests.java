package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.RepositoryMediaValidator;
import io.github.core607.poketto.content.RepositoryMoveRequest;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryWriteAuthorizationTests {
    @TempDir
    Path directory;

    @Test
    void writeFetchesBeforeAuthorizationAndKeepsTheGrantLockedThroughPush() throws Exception {
        var fixture = new Fixture();
        var result = fixture.service.apply(fixture.actor, fixture.workspace, fixture.patch());
        assertThat(result.committed()).isTrue();
        assertThat(fixture.pushes).isEqualTo(1);
        assertThat(fixture.repositories.remoteHead(fixture.workspace).name()).isEqualTo(result.commit());
    }

    @Test
    void revocationDuringFetchPreventsTheWrite() throws Exception {
        var fixture = new Fixture();
        fixture.revokeAfterFetch = true;
        assertThatThrownBy(() -> fixture.service.apply(fixture.actor, fixture.workspace, fixture.patch()))
                .isInstanceOf(SecurityException.class)
                .hasMessage("revoked");
        assertThat(fixture.pushes).isZero();
        assertThat(fixture.repositories.remoteHead(fixture.workspace).name()).isEqualTo(fixture.head);
    }

    @Test
    void movePlanFetchesBeforeAuthorization() throws Exception {
        var fixture = new Fixture();
        var result = fixture.service.plan(fixture.actor, fixture.workspace, fixture.move());
        assertThat(result.relocations()).containsEntry("private/source.md", "private/moved.md");
        assertThat(fixture.pushes).isZero();
    }

    @Test
    void revocationDuringFetchPreventsReturningTheMovePlan() throws Exception {
        var fixture = new Fixture();
        fixture.revokeAfterFetch = true;
        assertThatThrownBy(() -> fixture.service.plan(fixture.actor, fixture.workspace, fixture.move()))
                .isInstanceOf(SecurityException.class)
                .hasMessage("revoked");
        assertThat(fixture.pushes).isZero();
    }

    private final class Fixture {
        private final WorkspaceId workspace = WorkspaceId.random();
        private final AuthPrincipal actor = mock(AuthPrincipal.class);
        private final RemoteRepositoryFixture repositories;
        private final JGitRepositoryPatchService service;
        private final String head;
        private int authorizationDepth;
        private int pushes;
        private boolean revoked;
        private boolean revokeAfterFetch;

        Fixture() throws Exception {
            repositories = new RemoteRepositoryFixture(directory, transport());
            head = repositories
                    .commitRemote(
                            workspace, Map.of("private/source.md", "Private fixture".getBytes(StandardCharsets.UTF_8)))
                    .name();
            var auth = mock(AuthService.class);
            when(actor.kind()).thenReturn(AuthPrincipal.Kind.ACCOUNT);
            when(actor.subjectId()).thenReturn(UUID.randomUUID());
            when(auth.withAuthorization(any(), any(), any(), any())).thenAnswer(call -> {
                if (revoked) {
                    throw new SecurityException("revoked");
                }
                authorizationDepth++;
                try {
                    return ((Supplier<?>) call.getArgument(3)).get();
                } finally {
                    authorizationDepth--;
                }
            });
            service = new JGitRepositoryPatchService(
                    repositories.authority(),
                    auth,
                    Clock.systemUTC(),
                    (id, snapshot) -> {},
                    (id, snapshot) -> {},
                    mock(RepositoryMediaValidator.class));
        }

        RepositoryPatch patch() {
            return new RepositoryPatch(
                    Optional.of(head),
                    List.of(new RepositoryTextChange("private/new.md", true, Optional.empty(), Optional.of("new"))));
        }

        RepositoryMoveRequest move() {
            return new RepositoryMoveRequest(head, "private/source.md", "private/moved.md");
        }

        private RemoteGitTransport transport() {
            var delegate = new JGitRemoteGitTransport();
            return new RemoteGitTransport() {
                @Override
                public ObjectId fetchMain(Repository repository, RepositoryBinding binding) {
                    assertThat(authorizationDepth)
                            .as("authorization transaction during fetch")
                            .isZero();
                    ObjectId result = delegate.fetchMain(repository, binding);
                    revoked = revokeAfterFetch;
                    return result;
                }

                @Override
                public PushStatus pushMain(
                        Repository repository,
                        RepositoryBinding binding,
                        ObjectId expectedCommit,
                        ObjectId candidateCommit) {
                    assertThat(authorizationDepth)
                            .as("authorization transaction through push")
                            .isPositive();
                    pushes++;
                    return delegate.pushMain(repository, binding, expectedCommit, candidateCommit);
                }
            };
        }
    }
}
