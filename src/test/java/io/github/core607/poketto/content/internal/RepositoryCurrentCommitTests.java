package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryCurrentCommitTests {
    @TempDir
    Path directory;

    @Test
    void remoteHeadDoesNotDependOnDirectoryOrMediaIndexParsing() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        WorkspaceId workspace = WorkspaceId.random();
        var actor = mock(AuthPrincipal.class);
        var auth = mock(AuthService.class);
        when(auth.withAuthorization(any(), any(), any(), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(3)).get());
        var reader = new AuthorizedRepositoryReader(auth, new JGitRepositoryContentReader(fixture.authority()));
        assertThat(reader.currentCommit(actor, workspace)).isEmpty();
        var first = fixture.commitRemote(
                workspace, Map.of(RepositoryMediaIndex.PATH, "broken index".getBytes(StandardCharsets.UTF_8)));
        assertThat(reader.currentCommit(actor, workspace)).contains(first.name());
        var next = fixture.commitRemote(workspace, Map.of("new.md", "new".getBytes(StandardCharsets.UTF_8)));
        assertThat(reader.currentCommit(actor, workspace)).contains(next.name());
    }

    @Test
    void revocationWhileFetchingPreventsDisclosingTheRemoteCommit() throws Exception {
        var auth = mock(AuthService.class);
        var transport = spy(new JGitRemoteGitTransport());
        var fixture = new RemoteRepositoryFixture(directory, transport);
        WorkspaceId workspace = WorkspaceId.random();
        var actor = mock(AuthPrincipal.class);
        fixture.commitRemote(workspace, Map.of("private.md", "private".getBytes(StandardCharsets.UTF_8)));
        doAnswer(call -> {
                    Object head = call.callRealMethod();
                    doThrow(new AuthException(AuthException.Code.DENIED))
                            .when(auth)
                            .withAuthorization(any(), any(), any(), any());
                    return head;
                })
                .when(transport)
                .fetchMain(any(), any());
        var reader = new AuthorizedRepositoryReader(auth, new JGitRepositoryContentReader(fixture.authority()));
        assertThatThrownBy(() -> reader.currentCommit(actor, workspace)).isInstanceOf(AuthException.class);
    }
}
