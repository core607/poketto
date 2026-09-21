package io.github.core607.poketto.spaces;

import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.GitHubConnectionException;
import io.github.core607.poketto.content.GitHubRepositoryReconnections;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Set;
import java.util.concurrent.Semaphore;

/** Existing owners can restore their repository access independently of site creation eligibility. */
public final class GitHubSpaceReconnection {
    private final Accounts accounts;
    private final AuthService auth;
    private final GitHubRepositoryReconnections repositories;
    private final Semaphore admission = new Semaphore(2);

    public GitHubSpaceReconnection(Accounts accounts, AuthService auth, GitHubRepositoryReconnections repositories) {
        this.accounts = accounts;
        this.auth = auth;
        this.repositories = repositories;
    }

    public GitHubRepositoryReconnections.Status status(AuthPrincipal actor, WorkspaceId workspace) {
        accounts.account(actor);
        return auth.withAuthorization(
                actor, workspace, Set.of(Capability.MANAGE_KEYS), () -> repositories.status(actor, workspace));
    }

    public void reconnect(AuthPrincipal actor, WorkspaceId workspace, String repositoryName) {
        accounts.account(actor);
        auth.authorize(actor, workspace, Capability.MANAGE_KEYS);
        if (!admission.tryAcquire()) {
            throw new GitHubConnectionException(GitHubConnectionException.Code.BUSY);
        }
        try {
            GitHubRepositoryReconnections.Prepared prepared = repositories.prepare(actor, workspace, repositoryName);
            accounts.withAccount(
                    actor,
                    () -> auth.withAuthorization(actor, workspace, Set.of(Capability.MANAGE_KEYS), () -> {
                        repositories.apply(actor, workspace, prepared);
                        return null;
                    }));
        } finally {
            admission.release();
        }
    }
}
