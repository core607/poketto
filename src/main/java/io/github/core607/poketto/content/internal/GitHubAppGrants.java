package io.github.core607.poketto.content.internal;

import static io.github.core607.poketto.content.GitHubConnectionException.Code.AUTHORIZATION_CHANGED;
import static io.github.core607.poketto.content.GitHubConnectionException.Code.AUTHORIZATION_REQUIRED;
import static io.github.core607.poketto.content.GitHubConnectionException.Code.BUSY;
import static io.github.core607.poketto.content.GitHubConnectionException.Code.IDENTITY_CHANGED;

import io.github.core607.poketto.content.GitHubConnectionException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runtime provider authorization. Site-group changes do not alter an existing GitHub grant. */
final class GitHubAppGrants {
    private final GitHubAppGrantStore store;
    private final GitHubAppOAuth oauth;
    private final GitHubAppRepositories repositories;
    private final Clock clock;

    GitHubAppGrants(GitHubAppGrantStore store, GitHubAppOAuth oauth, GitHubAppRepositories repositories, Clock clock) {
        this.store = store;
        this.oauth = oauth;
        this.repositories = repositories;
        this.clock = clock;
    }

    Access verifiedAccess(UUID account) {
        requireOutsideTransaction();
        Access access = access(account);
        GitHubAppRepositories.Owner owner;
        try {
            owner = repositories.currentUser(access.token());
        } catch (GitHubConnectionException failure) {
            if (failure.code() == AUTHORIZATION_REQUIRED || failure.code() == IDENTITY_CHANGED) {
                store.revoke(account, access.version());
            }
            throw failure;
        }
        if (owner.id() != access.owner().id()) {
            store.revoke(account, access.version());
            throw new GitHubConnectionException(IDENTITY_CHANGED);
        }
        Access verified = new Access(account, access.version(), owner, access.token());
        requireCurrent(verified);
        return verified;
    }

    void requireCurrent(Access access) {
        GitHubAppGrantStore.Grant current =
                store.find(access.account()).orElseThrow(() -> new GitHubConnectionException(AUTHORIZATION_CHANGED));
        if (current.state() != GitHubAppGrantStore.State.ACTIVE || current.version() != access.version()) {
            throw new GitHubConnectionException(AUTHORIZATION_CHANGED);
        }
        if (current.ownerId() != access.owner().id()) {
            throw new GitHubConnectionException(AUTHORIZATION_CHANGED);
        }
    }

    private Access access(UUID account) {
        store.expireRefresh(account);
        GitHubAppGrantStore.Grant grant =
                store.find(account).orElseThrow(() -> new GitHubConnectionException(AUTHORIZATION_REQUIRED));
        if (grant.state() == GitHubAppGrantStore.State.REFRESHING) {
            throw new GitHubConnectionException(BUSY);
        }
        if (grant.state() != GitHubAppGrantStore.State.ACTIVE) {
            throw new GitHubConnectionException(AUTHORIZATION_REQUIRED);
        }
        GitHubAppOAuth.Tokens tokens = store.tokens(grant);
        if (expiring(tokens.accessExpiresAt())) {
            grant = refresh(grant, tokens);
            tokens = store.tokens(grant);
        }
        return new Access(
                account,
                grant.version(),
                new GitHubAppRepositories.Owner(grant.ownerId(), grant.login(), "User"),
                tokens.accessToken());
    }

    private GitHubAppGrantStore.Grant refresh(GitHubAppGrantStore.Grant grant, GitHubAppOAuth.Tokens tokens) {
        if (tokens.refreshToken() == null || !tokens.refreshExpiresAt().isAfter(clock.instant())) {
            store.revoke(grant.account(), grant.version());
            throw new GitHubConnectionException(AUTHORIZATION_REQUIRED);
        }
        GitHubAppGrantStore.Grant lease =
                store.claimRefresh(grant).orElseThrow(() -> new GitHubConnectionException(BUSY));
        GitHubAppOAuth.Tokens replacement;
        try {
            replacement = oauth.refresh(tokens.refreshToken());
        } catch (GitHubConnectionException failure) {
            // Even an outage can hide a consumed refresh token. Never retry that token after losing the response.
            if (failure.code() == BUSY) {
                store.releaseRefresh(lease);
            } else {
                store.failedRefresh(lease);
            }
            throw failure;
        }
        return store.refreshed(lease, replacement);
    }

    private boolean expiring(Instant expiry) {
        return expiry != null && !expiry.isAfter(clock.instant().plusSeconds(60));
    }

    private static void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("GitHub provider authorization must run outside a database transaction");
        }
    }

    record Access(UUID account, long version, GitHubAppRepositories.Owner owner, String token) {
        @Override
        public String toString() {
            return "GitHubGrantAccess[version=" + version + "]";
        }
    }
}
