package io.github.core607.poketto.spaces;

import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.GitHubConnectionException;
import io.github.core607.poketto.content.GitHubRepositoryProvisioning;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** Records remote creation before the separate installation and workspace-initialization steps. */
public final class GitHubSpaceCreation {
    private static final Logger log = LoggerFactory.getLogger(GitHubSpaceCreation.class);
    private final Accounts accounts;
    private final GitHubRepositoryProvisioning provider;
    private final GitHubCreationStore store;
    private final Clock clock;
    private final Semaphore admission = new Semaphore(2);

    public GitHubSpaceCreation(
            JdbcTemplate jdbc, Accounts accounts, GitHubRepositoryProvisioning provider, Clock clock) {
        this.accounts = accounts;
        this.provider = provider;
        this.clock = clock;
        this.store = new GitHubCreationStore(jdbc, clock);
    }

    public Result status(AuthPrincipal actor, UUID request) {
        accounts.account(actor);
        return result(store.find(actor.accountId(), Objects.requireNonNull(request, "Request ID is required"))
                .orElseThrow(() -> new AuthException(AuthException.Code.DENIED)));
    }

    public Result create(AuthPrincipal actor, Request request) {
        accounts.requireCreator(actor);
        Objects.requireNonNull(request, "GitHub space details are required");
        Optional<GitHubCreationStore.Attempt> previous = store.find(actor.accountId(), request.requestId());
        if (previous.isPresent()) {
            GitHubCreationStore.Attempt attempt = previous.orElseThrow();
            attempt.requireSame(request);
            if (attempt.finished() || attempt.blocksClaim(clock.instant())) {
                return result(attempt);
            }
        }
        if (!admission.tryAcquire()) {
            throw new GitHubConnectionException(GitHubConnectionException.Code.BUSY);
        }
        try {
            GitHubRepositoryProvisioning.Owner owner = provider.verifiedOwner(actor);
            if (owner.id() != request.githubOwnerId()) {
                throw new GitHubConnectionException(GitHubConnectionException.Code.IDENTITY_CHANGED);
            }
            UUID lease = UUID.randomUUID();
            GitHubCreationStore.Attempt attempt = accounts.withAccount(actor, () -> {
                accounts.requireCreator(actor);
                provider.requireCurrent(actor, owner);
                return store.claim(actor.accountId(), request, owner, lease);
            });
            return Objects.equals(attempt.lease(), lease) ? run(actor, owner, attempt) : result(attempt);
        } finally {
            admission.release();
        }
    }

    private Result run(
            AuthPrincipal actor, GitHubRepositoryProvisioning.Owner owner, GitHubCreationStore.Attempt attempt) {
        try {
            Optional<GitHubRepositoryProvisioning.Repository> repository = attempt.creationRequested()
                    ? provider.reconcile(actor, owner, attempt.repositoryName(), attempt.marker())
                    : Optional.of(provider.create(
                            actor,
                            owner,
                            attempt.repositoryName(),
                            attempt.marker(),
                            () -> beforeCreate(actor, owner, attempt)));
            if (repository.isEmpty()) {
                return result(store.failed(attempt, "CREATION_UNCERTAIN", false, null));
            }
            return result(store.recordRepository(attempt, repository.orElseThrow()));
        } catch (GitHubConnectionException failure) {
            return result(providerFailure(attempt, failure));
        } catch (AuthException denied) {
            store.failed(attempt, "ACCOUNT_UNAVAILABLE", !attempt.creationRequested(), Stage.BLOCKED);
            throw denied;
        } catch (RuntimeException failure) {
            log.warn("GitHub creation attempt interrupted", failure);
            store.failed(attempt, "UNAVAILABLE", false, null);
            throw new GitHubConnectionException(GitHubConnectionException.Code.UNAVAILABLE, failure);
        }
    }

    private void beforeCreate(
            AuthPrincipal actor, GitHubRepositoryProvisioning.Owner owner, GitHubCreationStore.Attempt attempt) {
        accounts.withAccount(actor, () -> {
            accounts.requireCreator(actor);
            provider.requireCurrent(actor, owner);
            store.beginCreate(attempt);
            return null;
        });
    }

    private GitHubCreationStore.Attempt providerFailure(
            GitHubCreationStore.Attempt attempt, GitHubConnectionException failure) {
        boolean disconnected =
                switch (failure.code()) {
                    case AUTHORIZATION_REQUIRED, AUTHORIZATION_CHANGED, IDENTITY_CHANGED -> true;
                    default -> false;
                };
        boolean rejected = failure.code() == GitHubConnectionException.Code.CREATION_REJECTED;
        boolean notCreated = !attempt.creationRequested()
                && (disconnected || rejected || failure.code() == GitHubConnectionException.Code.BUSY);
        Stage stopped = disconnected ? Stage.DISCONNECTED : rejected ? Stage.REJECTED : null;
        return store.failed(attempt, failure.code().name(), notCreated, stopped);
    }

    private Result result(GitHubCreationStore.Attempt attempt) {
        Instant now = clock.instant();
        Stage stage = attempt.stage();
        if (stage == Stage.CREATING && !attempt.blocksClaim(now)) {
            stage = Stage.UNCERTAIN;
        }
        int retry = attempt.blocksClaim(now)
                ? (int) Math.min(
                        300,
                        Math.max(
                                1, Duration.between(now, attempt.leaseExpires()).toSeconds()))
                : 0;
        return new Result(
                attempt.request(),
                attempt.workspace().value(),
                stage,
                attempt.repositoryId(),
                attempt.canonicalUri(),
                attempt.failure(),
                retry);
    }

    public enum Stage {
        PREPARING,
        CREATING,
        UNCERTAIN,
        AWAITING_INSTALLATION,
        DISCONNECTED,
        BLOCKED,
        REJECTED
    }

    /** A prospective workspace ID is not evidence that the workspace or its owner membership exists. */
    public record Result(
            UUID requestId,
            UUID workspaceId,
            Stage stage,
            Long repositoryId,
            String repository,
            String failureCode,
            int retryAfterSeconds) {}

    public record Request(UUID requestId, String displayName, String slug, long githubOwnerId, String repositoryName) {
        public Request {
            Objects.requireNonNull(requestId, "Request ID is required");
            if (displayName == null || displayName.isBlank() || displayName.length() > 120) {
                throw new IllegalArgumentException("Space display name must contain 1-120 characters");
            }
            displayName = displayName.strip();
            if (slug == null || !slug.matches("[a-z0-9][a-z0-9-]{1,62}[a-z0-9]")) {
                throw new IllegalArgumentException("Space slug must contain 3-64 lowercase name characters");
            }
            if (githubOwnerId <= 0) {
                throw new IllegalArgumentException("Confirmed GitHub owner is required");
            }
            if (!validRepositoryName(repositoryName)) {
                throw new IllegalArgumentException("GitHub repository name must contain 1-100 ASCII name characters");
            }
        }

        private static boolean validRepositoryName(String name) {
            return name != null
                    && name.matches("[A-Za-z0-9_.-]{1,100}")
                    && !name.equals(".")
                    && !name.equals("..")
                    && !name.equals("-");
        }
    }
}
