package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotExportAuthorizationTests {
    @TempDir
    Path directory;

    @Test
    void resolutionAndPackingLeaveAuthorizationTransactionsBeforeReadingGit() throws Exception {
        var fixture = new Fixture();
        assertThat(fixture.exports.resolve(fixture.actor, fixture.workspace, Optional.empty()))
                .isEqualTo(fixture.head);
        var exported = fixture.exports.create(fixture.actor, fixture.workspace, Optional.empty());
        assertThat(exported.commit()).isEqualTo(fixture.head);
        assertThat(directory.resolve("exports").resolve(exported.exportId() + ".bundle"))
                .isRegularFile();
        fixture.exports.release(exported.exportId());
        assertThat(directory.resolve("exports")).isEmptyDirectory();
    }

    @Test
    void revocationWhileReadingGitPreventsCommitDisclosure() throws Exception {
        var fixture = new Fixture();
        fixture.revokeAfterRead = true;
        assertThatThrownBy(() -> fixture.exports.resolve(fixture.actor, fixture.workspace, Optional.empty()))
                .isInstanceOf(SecurityException.class)
                .hasMessage("revoked");
        assertThat(directory.resolve("exports")).doesNotExist();
    }

    @Test
    void revocationWhilePackingRemovesTheUnacknowledgedBundle() throws Exception {
        var fixture = new Fixture();
        fixture.revokeAfterRead = true;
        assertThatThrownBy(() -> fixture.exports.create(fixture.actor, fixture.workspace, Optional.empty()))
                .isInstanceOf(SecurityException.class)
                .hasMessage("revoked");
        assertThat(directory.resolve("exports")).isEmptyDirectory();
    }

    @Test
    void failedAuthorizationCommitCannotLeaveAnAcknowledgedBundle() throws Exception {
        var fixture = new Fixture();
        fixture.failCommit = true;
        assertThatThrownBy(() -> fixture.exports.create(fixture.actor, fixture.workspace, Optional.empty()))
                .isInstanceOf(SecurityException.class)
                .hasMessage("transaction failed");
        assertThat(directory.resolve("exports")).isEmptyDirectory();
    }

    @Test
    void changedRepositoryBindingCannotReleaseAnExportPreparedWithOldCredentials() throws Exception {
        var fixture = new Fixture();
        fixture.revokeCredentialsAfterRead = true;
        assertThatThrownBy(() -> fixture.exports.create(fixture.actor, fixture.workspace, Optional.empty()))
                .isInstanceOf(ContentRepositoryException.class)
                .hasMessage("binding revoked");
        assertThat(directory.resolve("exports")).isEmptyDirectory();
    }

    private final class Fixture {
        private final WorkspaceId workspace = WorkspaceId.random();
        private final AuthPrincipal actor = mock(AuthPrincipal.class);
        private final JGitRepositorySnapshotExports exports;
        private final String head;
        private boolean guarded;
        private boolean revoked;
        private boolean revokeAfterRead;
        private boolean failCommit;
        private boolean revokeCredentialsAfterRead;
        private boolean credentialsRevoked;

        private Fixture() throws Exception {
            var repositories = new RemoteRepositoryFixture(directory);
            head = repositories
                    .commitRemote(
                            workspace, Map.of("private/source.md", "Private fixture".getBytes(StandardCharsets.UTF_8)))
                    .name();
            var auth = mock(AuthService.class);
            when(auth.withAuthorization(any(), any(), any(), any())).thenAnswer(call -> {
                if (revoked) {
                    throw new SecurityException("revoked");
                }
                guarded = true;
                try {
                    Object result = ((Supplier<?>) call.getArgument(3)).get();
                    if (failCommit && result instanceof RepositorySnapshotExports.Export) {
                        throw new SecurityException("transaction failed");
                    }
                    return result;
                } finally {
                    guarded = false;
                }
            });
            RepositoryAuthority authority = spy(repositories.authority());
            doAnswer(call -> {
                        if (credentialsRevoked) {
                            throw new ContentRepositoryException("binding revoked");
                        }
                        return call.callRealMethod();
                    })
                    .when(authority)
                    .withPreparedCredentials(any(), any());
            doAnswer(call -> {
                        assertThat(guarded)
                                .as("database authorization guard during repository I/O")
                                .isFalse();
                        Object result = call.callRealMethod();
                        revoked = revokeAfterRead;
                        credentialsRevoked = revokeCredentialsAfterRead;
                        return result;
                    })
                    .when(authority)
                    .readObjects(any(), any(), any());
            exports = new JGitRepositorySnapshotExports(
                    authority,
                    auth,
                    directory.toRealPath().resolve("exports"),
                    1024 * 1024,
                    Duration.ofSeconds(5),
                    mock(PublicContentSnapshots.class));
        }
    }
}
