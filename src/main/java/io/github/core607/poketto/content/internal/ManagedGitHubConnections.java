package io.github.core607.poketto.content.internal;

import static io.github.core607.poketto.content.GitHubConnectionException.Code.AUTHORIZATION_CHANGED;
import static io.github.core607.poketto.content.GitHubConnectionException.Code.AUTHORIZATION_REQUIRED;
import static io.github.core607.poketto.content.GitHubConnectionException.Code.UNAVAILABLE;

import io.github.core607.poketto.auth.AccountIdentity;
import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.GitHubConnectionException;
import io.github.core607.poketto.content.GitHubConnections;
import io.github.core607.poketto.content.GitHubRepositoryProvisioning;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Account policy surrounds provider I/O; token persistence commits only with a current account. */
final class ManagedGitHubConnections implements GitHubConnections, GitHubRepositoryProvisioning, AutoCloseable {
    private final Accounts accounts;
    private final GitHubAppGrantStore store;
    private final GitHubAppOAuth oauth;
    private final GitHubAppRepositories repositories;
    private final GitHubAppHttp http;
    private final Clock clock;
    private final GitHubAppInstallations installations;
    private final GitHubAppGrants grants;

    ManagedGitHubConnections(
            Accounts accounts,
            GitHubAppGrantStore store,
            GitHubAppOAuth oauth,
            GitHubAppRepositories repositories,
            GitHubAppHttp http,
            Clock clock,
            GitHubAppInstallations installations) {
        this.accounts = accounts;
        this.store = store;
        this.oauth = oauth;
        this.repositories = repositories;
        this.http = http;
        this.clock = clock;
        this.installations = installations;
        this.grants = oauth == null ? null : new GitHubAppGrants(store, oauth, repositories, clock);
    }

    static ManagedGitHubConnections disabled(Accounts accounts) {
        return new ManagedGitHubConnections(accounts, null, null, null, null, Clock.systemUTC(), null);
    }

    @Override
    public Owner verifiedOwner(AuthPrincipal actor) {
        accounts.requireCreator(actor);
        requireAvailable();
        GitHubAppGrants.Access access = grants.verifiedAccess(actor.accountId());
        accounts.requireCreator(actor);
        return new Owner(access.owner().id(), access.owner().login(), access.version());
    }

    @Override
    public void requireCurrent(AuthPrincipal actor, Owner owner) {
        accounts.account(actor);
        requireAvailable();
        store.lockActive(actor.accountId(), owner.grantVersion(), owner.id());
    }

    @Override
    public Repository create(AuthPrincipal actor, Owner owner, String name, UUID marker, Runnable beforeCreate) {
        GitHubAppGrants.Access access = provisioningAccess(actor, owner);
        return provisioned(repositories.create(owner.id(), name, marker, access.token(), beforeCreate));
    }

    @Override
    public Optional<Repository> reconcile(AuthPrincipal actor, Owner owner, String name, UUID marker) {
        GitHubAppGrants.Access access = provisioningAccess(actor, owner);
        return repositories
                .reconcile(owner.id(), name, marker, access.token())
                .map(ManagedGitHubConnections::provisioned);
    }

    private GitHubAppGrants.Access provisioningAccess(AuthPrincipal actor, Owner owner) {
        accounts.requireCreator(actor);
        requireAvailable();
        GitHubAppGrants.Access access = grants.verifiedAccess(actor.accountId());
        if (access.owner().id() != owner.id() || access.version() != owner.grantVersion()) {
            throw new GitHubConnectionException(AUTHORIZATION_CHANGED);
        }
        return access;
    }

    private static Repository provisioned(GitHubAppRepositories.Repository repository) {
        return new Repository(
                repository.id(), repository.owner().id(), repository.owner().login(), repository.name());
    }

    TokenLease repositoryToken(UUID account, long ownerId, long installationId, long repositoryId) {
        requireAvailable();
        GitHubAppGrants.Access access = grants.verifiedAccess(account);
        if (access.owner().id() != ownerId) {
            throw new GitHubConnectionException(GitHubConnectionException.Code.REPOSITORY_CHANGED);
        }
        GitHubAppInstallations.Token token = installations.issue(installationId, ownerId, repositoryId);
        grants.requireCurrent(access);
        Instant issuedAt = clock.instant();
        return new TokenLease(account, access.version(), ownerId, token, issuedAt, issuedAt.plusSeconds(60));
    }

    void requireLease(TokenLease lease) {
        grants.requireCurrent(lease.account(), lease.grantVersion(), lease.ownerId());
        Instant now = clock.instant();
        if (now.isBefore(lease.issuedAt())
                || !now.isBefore(lease.validUntil())
                || !now.plusSeconds(30).isBefore(lease.token().expiresAt())) {
            throw new GitHubConnectionException(UNAVAILABLE);
        }
    }

    record TokenLease(
            UUID account,
            long grantVersion,
            long ownerId,
            GitHubAppInstallations.Token token,
            Instant issuedAt,
            Instant validUntil) {
        @Override
        public String toString() {
            return "GitHubRepositoryTokenLease[redacted]";
        }
    }

    @Override
    public Status status(AuthPrincipal actor) {
        AccountIdentity account = accounts.account(actor);
        boolean eligible = account.group().mayCreateSpace();
        if (oauth == null) {
            return new Status(false, eligible, State.DISABLED, null, null, 0);
        }
        store.expireRefresh(actor.accountId());
        return store.find(actor.accountId())
                .map(grant -> status(grant, eligible))
                .orElseGet(() -> new Status(true, eligible, State.NOT_CONNECTED, null, null, 0));
    }

    @Override
    public Authorization begin(AuthPrincipal actor) {
        accounts.account(actor);
        requireAvailable();
        long version = store.find(actor.accountId())
                .map(GitHubAppGrantStore.Grant::version)
                .orElse(0L);
        requireEligibility(actor, version);
        GitHubAppOAuth.Flow flow = oauth.begin();
        return new Authorization(
                oauth.authorization(flow).toString(),
                flow.state(),
                flow.verifier(),
                flow.issuedAt(),
                actor.accountId(),
                actor.credentialVersion(),
                version);
    }

    @Override
    public void complete(AuthPrincipal actor, Authorization authorization, String code, Runnable requireSession) {
        accounts.account(actor);
        requireAvailable();
        requireActor(actor, authorization);
        requireEligibility(actor, authorization.grantVersion());
        requireSession.run();
        var flow = new GitHubAppOAuth.Flow(authorization.state(), authorization.verifier(), authorization.issuedAt());
        GitHubAppOAuth.Tokens tokens = oauth.exchange(flow, authorization.state(), code);
        GitHubAppRepositories.Owner owner = repositories.currentUser(tokens.accessToken());
        accounts.withAccount(actor, () -> {
            requireSession.run();
            requireActor(actor, authorization);
            requireEligibility(actor, authorization.grantVersion());
            store.authorize(actor.accountId(), authorization.grantVersion(), owner, tokens);
            return null;
        });
    }

    @Override
    public Status disconnect(AuthPrincipal actor, long expectedVersion) {
        accounts.account(actor);
        requireAvailable();
        accounts.withAccount(actor, () -> {
            if (expectedVersion == 0 && store.find(actor.accountId()).isEmpty()) {
                return null;
            }
            store.revoke(actor.accountId(), expectedVersion);
            return null;
        });
        return status(actor);
    }

    private void requireEligibility(AuthPrincipal actor, long priorVersion) {
        if (priorVersion == 0) {
            accounts.requireCreator(actor);
        }
        // Reconnecting an existing grant does not grant creation eligibility or revoke old memberships.
    }

    private void requireActor(AuthPrincipal actor, Authorization authorization) {
        Objects.requireNonNull(authorization, "GitHub authorization is required");
        if (!actor.accountId().equals(authorization.accountId())
                || actor.credentialVersion() != authorization.credentialVersion()) {
            throw new GitHubConnectionException(AUTHORIZATION_CHANGED);
        }
        Instant now = clock.instant();
        if (now.isBefore(authorization.issuedAt())
                || !now.isBefore(authorization.issuedAt().plusSeconds(600))) {
            throw new GitHubConnectionException(AUTHORIZATION_REQUIRED);
        }
    }

    private Status status(GitHubAppGrantStore.Grant grant, boolean eligible) {
        State state =
                switch (grant.state()) {
                    case ACTIVE -> State.CONNECTED;
                    case REFRESHING -> State.REFRESHING;
                    case REAUTHORIZATION -> State.REAUTHORIZATION;
                    case REVOKED -> State.DISCONNECTED;
                };
        if (grant.refreshExpiresAt() != null && !grant.refreshExpiresAt().isAfter(clock.instant())) {
            state = State.REAUTHORIZATION;
        }
        if (!store.matchesConfiguredApp(grant)) {
            state = State.REAUTHORIZATION;
        }
        return new Status(true, eligible, state, grant.ownerId(), grant.login(), grant.version());
    }

    private void requireAvailable() {
        if (oauth == null) {
            throw new GitHubConnectionException(UNAVAILABLE);
        }
    }

    @Override
    public void close() {
        if (http != null) {
            http.close();
        }
    }
}
