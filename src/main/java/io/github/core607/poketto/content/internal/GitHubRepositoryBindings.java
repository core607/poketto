package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.GitHubConnectionException;
import io.github.core607.poketto.content.RepositoryCoordinates;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/** Resolves App bindings to bounded, checked Git credentials. It never falls back to operator credentials. */
final class GitHubRepositoryBindings {
    private final JdbcTemplate jdbc;
    private final ManagedGitHubConnections github;

    GitHubRepositoryBindings(JdbcTemplate jdbc, ManagedGitHubConnections github) {
        this.jdbc = jdbc;
        this.github = github;
    }

    RepositoryBinding binding(WorkspaceId workspace) {
        try {
            return resolve(workspace);
        } catch (GitHubConnectionException failure) {
            throw unavailable(failure);
        }
    }

    private RepositoryBinding resolve(WorkspaceId workspace) {
        Optional<Stored> stored = find(workspace);
        if (stored.isEmpty()) {
            return null;
        }
        Stored expected = stored.orElseThrow();
        ManagedGitHubConnections.TokenLease lease = github.repositoryToken(
                expected.account(), expected.owner(), expected.installation(), expected.repository());
        GitHubAppRepositories.Repository repository = lease.token().repository();
        RepositoryCoordinates coordinates = RepositoryCoordinates.parse(
                "https://github.com/" + repository.owner().login() + "/" + repository.name() + ".git");
        if (!coordinates.canonicalUri().equals(expected.uri())) {
            throw new GitHubConnectionException(GitHubConnectionException.Code.REPOSITORY_CHANGED);
        }
        Runnable validation = () -> requireCurrent(workspace, expected, lease);
        validation.run();
        try {
            return new RepositoryBinding(
                    new URIish(coordinates.transportUri()),
                    new UsernamePasswordCredentialsProvider(
                            "x-access-token", lease.token().value()),
                    true,
                    validation);
        } catch (URISyntaxException invalid) {
            throw new GitHubConnectionException(GitHubConnectionException.Code.INVALID_RESPONSE, invalid);
        }
    }

    private void requireCurrent(WorkspaceId workspace, Stored expected, ManagedGitHubConnections.TokenLease lease) {
        try {
            github.requireLease(lease);
            if (!find(workspace).filter(expected::equals).isPresent()) {
                throw new GitHubConnectionException(GitHubConnectionException.Code.REPOSITORY_CHANGED);
            }
        } catch (GitHubConnectionException failure) {
            throw unavailable(failure);
        }
    }

    private static ContentRepositoryException unavailable(GitHubConnectionException failure) {
        return new ContentRepositoryException(
                "GitHub repository connection is unavailable; reconnect its authorization", failure);
    }

    private Optional<Stored> find(WorkspaceId workspace) {
        return jdbc
                .query(
                        """
                select canonical_uri,provider_identity,github_account_id,github_owner_id,github_installation_id
                from content_repository_bindings where workspace_id=? and credential_kind='GITHUB_APP'
                """,
                        (row, number) -> new Stored(
                                row.getString(1),
                                repositoryId(row.getString(2)),
                                row.getObject(3, UUID.class),
                                row.getLong(4),
                                row.getLong(5)),
                        workspace.value())
                .stream()
                .findFirst();
    }

    private static long repositoryId(String identity) {
        try {
            return Long.parseLong(identity.substring("github:".length()));
        } catch (NumberFormatException invalid) {
            throw new GitHubConnectionException(GitHubConnectionException.Code.INVALID_RESPONSE, invalid);
        }
    }

    private record Stored(String uri, long repository, UUID account, long owner, long installation) {
        @Override
        public String toString() {
            return "GitHubRepositoryBinding[redacted]";
        }
    }
}
