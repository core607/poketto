package io.github.core607.poketto.content;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Provider operations for a durable personal-space attempt. No provider token crosses this boundary. */
public interface GitHubRepositoryProvisioning {
    /** Verifies the current personal identity and grant outside relational transactions. */
    Owner verifiedOwner(AuthPrincipal actor);

    /** Locks and rechecks the grant in the caller's account transaction, without provider I/O. */
    void requireCurrent(AuthPrincipal actor, Owner owner);

    /**
     * Invokes beforeCreate immediately before the single creation request. The callback must commit
     * durable intent and recheck account, grant and attempt ownership. A returned result remains
     * evidence of creation even if authorization changes while the request is in flight.
     * BUSY and authorization/identity failures imply no accepted creation; an ambiguous provider
     * result uses CREATION_UNCERTAIN and must be reconciled rather than posted again.
     */
    Repository create(AuthPrincipal actor, Owner owner, String name, UUID marker, Runnable beforeCreate);

    /** Exact marker recovery only; absence does not authorize another creation request. */
    Optional<Repository> reconcile(AuthPrincipal actor, Owner owner, String name, UUID marker);

    /** Verifies repository identity and selected installation access outside a transaction; no token is returned. */
    PreparedBinding prepareBinding(AuthPrincipal actor, Owner owner, long repositoryId, String repositoryName);

    /** Installs the prepared binding in the caller's account/workspace transaction after rechecking its grant and expiry. */
    void install(AuthPrincipal actor, WorkspaceId workspace, PreparedBinding binding);

    record PreparedBinding(
            UUID accountId,
            Owner owner,
            Repository repository,
            long installationId,
            AccessEpochs accessEpochs,
            Instant preparedAt,
            Instant validUntil) {
        public PreparedBinding {
            if (accessEpochs == null) {
                throw new IllegalArgumentException("GitHub access epochs are required");
            }
            if (accountId == null || owner == null || repository == null || installationId <= 0) {
                throw new IllegalArgumentException("Verified GitHub installation is required");
            }
            if (repository.ownerId() != owner.id()) {
                throw new IllegalArgumentException("Verified GitHub installation owner does not match");
            }
            if (!validLifetime(preparedAt, validUntil)) {
                throw new IllegalArgumentException("GitHub binding validation must expire within sixty seconds");
            }
        }

        private static boolean validLifetime(Instant preparedAt, Instant validUntil) {
            return preparedAt != null
                    && validUntil != null
                    && validUntil.isAfter(preparedAt)
                    && !validUntil.isAfter(preparedAt.plusSeconds(60));
        }

        @Override
        public String toString() {
            return "PreparedGitHubBinding[redacted]";
        }
    }

    record AccessEpochs(long installation, long repository) {
        public AccessEpochs {
            if (installation < 0) {
                throw new IllegalArgumentException("GitHub installation epoch cannot be negative");
            }
            if (repository < 0) {
                throw new IllegalArgumentException("GitHub repository epoch cannot be negative");
            }
        }
    }

    record Owner(long id, String login, long grantVersion) {
        public Owner {
            if (id <= 0 || grantVersion <= 0 || login == null || !login.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}")) {
                throw new IllegalArgumentException("Verified GitHub owner is invalid");
            }
        }
    }

    record Repository(long id, long ownerId, String ownerLogin, String name) {
        public Repository {
            if (id <= 0 || ownerId <= 0 || ownerLogin == null || !ownerLogin.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}")) {
                throw new IllegalArgumentException("Verified GitHub repository owner is invalid");
            }
            if (name == null || !name.matches("[A-Za-z0-9_.-]{1,100}") || name.equals(".") || name.equals("..")) {
                throw new IllegalArgumentException("Verified GitHub repository name is invalid");
            }
        }

        public String canonicalUri() {
            // The provider gives the literal name; append the transport suffix before URL normalization.
            return RepositoryCoordinates.parse("https://github.com/" + ownerLogin + "/" + name + ".git")
                    .canonicalUri();
        }
    }
}
