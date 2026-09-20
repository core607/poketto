package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.core607.poketto.content.GitHubConnectionException;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePaths;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PreparedRepositoryCredentialsTests {
    @TempDir
    Path directory;

    @Test
    void preparationPrecedesTransactionsAndNestedWorkspacesCannotBorrowEachOthersBinding() throws Exception {
        WorkspaceId first = WorkspaceId.random();
        WorkspaceId second = WorkspaceId.random();
        List<WorkspaceId> preparations = new ArrayList<>();
        RepositoryBinding binding = binding(() -> {});
        var authority = new JGitRemoteRepositoryAuthority(
                new WorkspacePaths(directory),
                workspace -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    preparations.add(workspace);
                    return binding;
                },
                mock(RemoteGitTransport.class),
                2,
                Clock.systemUTC());
        String result = authority.withPreparedCredentials(first, () -> {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                return authority.withPreparedCredentials(first, () -> "same-transaction");
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        });
        assertThat(result).isEqualTo("same-transaction");
        assertThat(preparations).containsExactly(first);
        authority.withPreparedCredentials(
                first,
                () -> authority.withPreparedCredentials(
                        second, () -> authority.withPreparedCredentials(first, () -> "separate-workspace")));
        assertThat(preparations).containsExactly(first, first, second, first);
    }

    @Test
    void exceptionalActionsDiscardPreparedCredentialsAndNestedActionsRevalidateThem() throws Exception {
        WorkspaceId workspace = WorkspaceId.random();
        var revoked = new AtomicBoolean();
        RepositoryBinding binding = binding(() -> {
            if (revoked.get()) {
                throw new GitHubConnectionException(GitHubConnectionException.Code.AUTHORIZATION_CHANGED);
            }
        });
        List<WorkspaceId> preparations = new ArrayList<>();
        var authority = new JGitRemoteRepositoryAuthority(
                new WorkspacePaths(directory),
                current -> {
                    preparations.add(current);
                    return binding;
                },
                mock(RemoteGitTransport.class),
                2,
                Clock.systemUTC());
        assertThatThrownBy(() -> authority.withPreparedCredentials(workspace, () -> {
                    revoked.set(true);
                    return authority.withPreparedCredentials(workspace, () -> "must-not-run");
                }))
                .hasMessage("GitHub App: AUTHORIZATION_CHANGED");
        revoked.set(false);
        assertThat(authority.withPreparedCredentials(workspace, () -> "new-lease"))
                .isEqualTo("new-lease");
        assertThat(preparations).containsExactly(workspace, workspace);
    }

    @Test
    void revokedBindingNeverOpensGitTransport() throws Exception {
        Repository repository = mock(Repository.class);
        RepositoryBinding revoked = binding(() -> {
            throw new GitHubConnectionException(GitHubConnectionException.Code.AUTHORIZATION_CHANGED);
        });
        var transport = new JGitRemoteGitTransport();
        assertThatThrownBy(() -> transport.fetchMain(repository, revoked))
                .hasMessage("GitHub App: AUTHORIZATION_CHANGED");
        assertThatThrownBy(() -> transport.pushMain(repository, revoked, ObjectId.zeroId(), ObjectId.zeroId()))
                .hasMessage("GitHub App: AUTHORIZATION_CHANGED");
        verifyNoInteractions(repository);
    }

    private static RepositoryBinding binding(Runnable validation) throws Exception {
        return new RepositoryBinding(
                new URIish("https://github.com/example/notes.git"),
                new UsernamePasswordCredentialsProvider("x-access-token", "fixture-token"),
                true,
                validation);
    }
}
