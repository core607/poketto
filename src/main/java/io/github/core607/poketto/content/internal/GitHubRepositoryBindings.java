package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.GitHubConnectionException;
import io.github.core607.poketto.content.GitHubRepositoryProvisioning;
import io.github.core607.poketto.content.GitHubRepositoryReconnections;
import io.github.core607.poketto.content.RepositoryCoordinates;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Resolves App bindings to bounded, checked Git credentials. It never falls back to operator credentials. */
final class GitHubRepositoryBindings implements GitHubRepositoryReconnections {
    private final JdbcTemplate jdbc;
    private final ManagedGitHubConnections github;

    GitHubRepositoryBindings(JdbcTemplate jdbc, ManagedGitHubConnections github) {
        this.jdbc = jdbc;
        this.github = github;
    }

    @Override
    public Status status(AuthPrincipal actor, WorkspaceId workspace) {
        Stored stored = requireBinding(workspace);
        return new Status(stored.account().equals(actor.accountId()), stored.revoked());
    }

    @Override
    public Prepared prepare(AuthPrincipal actor, WorkspaceId workspace, String repositoryName) {
        Stored expected = requireBinding(workspace);
        if (!expected.account().equals(actor.accountId())) {
            throw new GitHubConnectionException(GitHubConnectionException.Code.AUTHORIZATION_CHANGED);
        }
        GitHubRepositoryProvisioning.PreparedBinding prepared =
                github.prepareReconnection(actor, expected.owner(), expected.repository(), repositoryName);
        return new Prepared(workspace, expected.version(), prepared);
    }

    @Override
    public void apply(AuthPrincipal actor, WorkspaceId workspace, Prepared prepared) {
        if (!workspace.equals(prepared.workspace())) {
            throw new GitHubConnectionException(GitHubConnectionException.Code.REPOSITORY_CHANGED);
        }
        GitHubRepositoryProvisioning.PreparedBinding binding = prepared.binding();
        github.requirePrepared(actor, binding);
        int changed;
        try {
            changed = jdbc.update(
                    """
                    update content_repository_bindings
                    set canonical_uri=?,github_installation_id=?,github_revoked=false,
                        github_binding_version=github_binding_version+1,updated_at=current_timestamp
                    where workspace_id=? and credential_kind='GITHUB_APP' and github_binding_version=?
                        and github_account_id=? and github_owner_id=? and provider_identity=?
                    """,
                    binding.repository().canonicalUri(),
                    binding.installationId(),
                    workspace.value(),
                    prepared.bindingVersion(),
                    actor.accountId(),
                    binding.owner().id(),
                    "github:" + binding.repository().id());
        } catch (DuplicateKeyException duplicate) {
            throw new GitHubConnectionException(GitHubConnectionException.Code.REPOSITORY_CHANGED, duplicate);
        }
        if (changed != 1) {
            throw new GitHubConnectionException(GitHubConnectionException.Code.REPOSITORY_CHANGED);
        }
    }

    private Stored requireBinding(WorkspaceId workspace) {
        return find(workspace)
                .orElseThrow(() -> new GitHubConnectionException(GitHubConnectionException.Code.REPOSITORY_CHANGED));
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
        if (expected.revoked()) {
            throw new GitHubConnectionException(GitHubConnectionException.Code.INSTALLATION_REQUIRED);
        }
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
        ContentRepositoryException.Recovery recovery =
                switch (failure.code()) {
                    case AUTHORIZATION_REQUIRED,
                            AUTHORIZATION_CHANGED,
                            IDENTITY_CHANGED,
                            REPOSITORY_CHANGED,
                            INSTALLATION_REQUIRED -> ContentRepositoryException.Recovery.RECONNECT;
                    default -> ContentRepositoryException.Recovery.RETRY;
                };
        String message = recovery == ContentRepositoryException.Recovery.RECONNECT
                ? "GitHub repository connection is unavailable; reconnect its authorization"
                : "GitHub repository access is temporarily unavailable; retry the operation";
        return new ContentRepositoryException(message, recovery, failure);
    }

    private Optional<Stored> find(WorkspaceId workspace) {
        return jdbc
                .query(
                        """
                select canonical_uri,provider_identity,github_account_id,github_owner_id,github_installation_id,
                    github_binding_version,github_revoked
                from content_repository_bindings where workspace_id=? and credential_kind='GITHUB_APP'
                """,
                        (row, number) -> new Stored(
                                row.getString(1),
                                repositoryId(row.getString(2)),
                                row.getObject(3, UUID.class),
                                row.getLong(4),
                                row.getLong(5),
                                row.getLong(6),
                                row.getBoolean(7)),
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

    private record Stored(
            String uri, long repository, UUID account, long owner, long installation, long version, boolean revoked) {
        @Override
        public String toString() {
            return "GitHubRepositoryBinding[redacted]";
        }
    }
}
