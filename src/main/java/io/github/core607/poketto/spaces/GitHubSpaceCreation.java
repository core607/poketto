package io.github.core607.poketto.spaces;

import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.GitHubConnectionException;
import io.github.core607.poketto.content.GitHubRepositoryProvisioning;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.RepositoryConnectionException;
import io.github.core607.poketto.content.RepositoryInitialization;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
import io.github.core607.poketto.workspace.WorkspaceRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Durable remote creation, verified workspace binding, and resumable template initialization. */
public final class GitHubSpaceCreation {
    private static final Logger log = LoggerFactory.getLogger(GitHubSpaceCreation.class);
    private final Accounts accounts;
    private final GitHubRepositoryProvisioning provider;
    private final GitHubCreationStore store;
    private final Clock clock;
    private final Semaphore admission = new Semaphore(2);
    private final AuthService auth;
    private final WorkspaceRegistry workspaces;
    private final RepositoryInitialization initialization;

    public GitHubSpaceCreation(
            JdbcTemplate jdbc,
            Accounts accounts,
            GitHubRepositoryProvisioning provider,
            Clock clock,
            AuthService auth,
            WorkspaceRegistry workspaces,
            RepositoryInitialization initialization) {
        this.accounts = accounts;
        this.provider = provider;
        this.clock = clock;
        this.store = new GitHubCreationStore(jdbc, clock);
        this.auth = auth;
        this.workspaces = workspaces;
        this.initialization = initialization;
    }

    public Result status(AuthPrincipal actor, UUID request) {
        accounts.account(actor);
        return result(store.find(actor.accountId(), Objects.requireNonNull(request, "Request ID is required"))
                .orElseThrow(() -> new AuthException(AuthException.Code.DENIED)));
    }

    public History history(AuthPrincipal actor, int offset) {
        accounts.account(actor);
        if (offset < 0 || offset > 100_000) {
            throw new IllegalArgumentException("Creation history offset must be between 0 and 100000");
        }
        List<GitHubCreationStore.Attempt> attempts = store.list(actor.accountId(), offset);
        List<Entry> items = attempts.stream()
                .limit(20)
                .map(attempt -> new Entry(
                        new Request(
                                attempt.request(),
                                attempt.name(),
                                attempt.slug(),
                                attempt.owner(),
                                attempt.repositoryName()),
                        result(attempt)))
                .toList();
        return new History(items, attempts.size() > 20 ? offset + 20 : null);
    }

    public record Entry(Request request, Result result) {}

    public record History(List<Entry> items, Integer nextOffset) {}

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

    public Result resume(AuthPrincipal actor, UUID requestId) {
        accounts.account(actor);
        GitHubCreationStore.Attempt previous = store.find(actor.accountId(), requestId)
                .orElseThrow(() -> new AuthException(AuthException.Code.DENIED));
        if (previous.stage() == Stage.READY || previous.blocksClaim(clock.instant())) {
            return result(previous);
        }
        requireCompletionAuthority(actor, previous);
        if (!admission.tryAcquire()) {
            throw new GitHubConnectionException(GitHubConnectionException.Code.BUSY);
        }
        try {
            GitHubRepositoryProvisioning.Owner owner = provider.verifiedOwner(actor);
            if (owner.id() != previous.owner()) {
                throw new GitHubConnectionException(GitHubConnectionException.Code.IDENTITY_CHANGED);
            }
            UUID lease = UUID.randomUUID();
            GitHubCreationStore.Attempt attempt = accounts.withAccount(actor, () -> {
                requireCompletionAuthority(actor, previous);
                provider.requireCurrent(actor, owner);
                return store.claimCompletion(actor.accountId(), requestId, owner.grantVersion(), lease);
            });
            return Objects.equals(attempt.lease(), lease) ? complete(actor, owner, attempt) : result(attempt);
        } finally {
            admission.release();
        }
    }

    private void requireCompletionAuthority(AuthPrincipal actor, GitHubCreationStore.Attempt attempt) {
        if (attempt.bound()) {
            auth.authorize(actor, attempt.workspace(), Capability.MANAGE_KEYS);
        } else {
            accounts.requireCreator(actor);
        }
    }

    private Result complete(
            AuthPrincipal actor, GitHubRepositoryProvisioning.Owner owner, GitHubCreationStore.Attempt attempt) {
        try {
            GitHubCreationStore.Attempt bound = attempt.bound() ? attempt : bind(actor, owner, attempt);
            requireCompletionAuthority(actor, bound);
            RepositoryInitialization.Outcome outcome =
                    initialization.apply(actor, bound.workspace(), () -> store.requireLease(bound));
            return accounts.withAccount(actor, () -> {
                provider.requireCurrent(actor, owner);
                return auth.withAuthorization(
                        actor,
                        bound.workspace(),
                        Set.of(Capability.MANAGE_KEYS),
                        () -> result(store.ready(bound, outcome.commit())));
            });
        } catch (GitHubConnectionException failure) {
            Stage stopped =
                    switch (failure.code()) {
                        case AUTHORIZATION_REQUIRED, AUTHORIZATION_CHANGED, IDENTITY_CHANGED -> Stage.DISCONNECTED;
                        default -> null;
                    };
            return result(store.completionFailed(attempt, failure.code().name(), stopped));
        } catch (RepositoryConnectionException failure) {
            return result(store.completionFailed(attempt, failure.code().name(), null));
        } catch (DataIntegrityViolationException conflict) {
            return result(store.completionFailed(attempt, "DUPLICATE", null));
        } catch (ContentRepositoryException
                | RepositoryConflictException
                | RepositoryWriteAmbiguousException unavailable) {
            return result(store.completionFailed(attempt, "INITIALIZATION_REQUIRED", null));
        } catch (AuthException denied) {
            store.completionFailed(attempt, "ACCOUNT_UNAVAILABLE", Stage.BLOCKED);
            throw denied;
        } catch (RuntimeException failure) {
            log.warn("GitHub space completion interrupted", failure);
            store.completionFailed(attempt, "UNAVAILABLE", null);
            throw new GitHubConnectionException(GitHubConnectionException.Code.UNAVAILABLE, failure);
        }
    }

    private GitHubCreationStore.Attempt bind(
            AuthPrincipal actor, GitHubRepositoryProvisioning.Owner owner, GitHubCreationStore.Attempt attempt) {
        GitHubRepositoryProvisioning.PreparedBinding prepared =
                provider.prepareBinding(actor, owner, attempt.repositoryId(), attempt.repositoryName());
        boolean matches = prepared.repository().id() == attempt.repositoryId()
                && prepared.owner().id() == attempt.owner();
        if (!matches) {
            throw new GitHubConnectionException(GitHubConnectionException.Code.REPOSITORY_CHANGED);
        }
        return accounts.withAccount(actor, () -> {
            accounts.requireCreator(actor);
            store.requireLease(attempt);
            provider.requireCurrent(actor, owner);
            workspaces.create(attempt.workspace(), attempt.name(), attempt.slug());
            auth.establishWorkspaceOwner(actor, attempt.workspace());
            provider.install(actor, attempt.workspace(), prepared);
            return store.bound(attempt, prepared.repository().canonicalUri());
        });
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
        boolean installationRequired = failure.code() == GitHubConnectionException.Code.INSTALLATION_REQUIRED;
        boolean notCreated = !attempt.creationRequested()
                && (disconnected
                        || rejected
                        || installationRequired
                        || failure.code() == GitHubConnectionException.Code.BUSY);
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
                attempt.bound(),
                attempt.initializationCommit(),
                attempt.failure(),
                retry);
    }

    public enum Stage {
        PREPARING,
        CREATING,
        UNCERTAIN,
        AWAITING_INSTALLATION,
        VALIDATING_INSTALLATION,
        INITIALIZING,
        READY,
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
            boolean workspaceCreated,
            String initializationCommit,
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
