package io.github.core607.poketto.spaces;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.RegistrationService;
import io.github.core607.poketto.content.RepositoryConnectionException;
import io.github.core607.poketto.content.RepositoryConnections;
import io.github.core607.poketto.content.RepositoryCoordinates;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspaceRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Account-level repository connections, durable across response loss and process restarts. No remote writes occur. */
public final class SpaceCreationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RegistrationService accounts;
    private final AuthService auth;
    private final WorkspaceRegistry workspaces;
    private final RepositoryConnections repositories;
    private final Clock clock;
    private final Semaphore admission = new Semaphore(2);

    public SpaceCreationService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            RegistrationService accounts,
            AuthService auth,
            WorkspaceRegistry workspaces,
            RepositoryConnections repositories,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.accounts = accounts;
        this.auth = auth;
        this.workspaces = workspaces;
        this.repositories = repositories;
        this.clock = clock;
    }

    public boolean available(AuthPrincipal actor) {
        accounts.account(actor);
        return repositories.available();
    }

    public ConnectionInfo connectionInfo(AuthPrincipal actor, WorkspaceId workspace) {
        accounts.account(actor);
        return auth.withAuthorization(actor, workspace, Set.of(Capability.MANAGE_KEYS), () -> {
            Optional<RepositoryConnections.ConnectionInfo> binding = repositories.connectionInfo(workspace);
            return new ConnectionInfo(binding.isPresent(), repositories.available(), binding.orElse(null));
        });
    }

    public record ConnectionInfo(
            boolean managed, boolean rotationAvailable, RepositoryConnections.ConnectionInfo binding) {}

    public void rotateCredentials(AuthPrincipal actor, WorkspaceId workspace, String username, String token) {
        accounts.account(actor);
        auth.authorize(actor, workspace, Capability.MANAGE_KEYS);
        if (!admission.tryAcquire()) {
            throw new RepositoryConnectionException(RepositoryConnectionException.Code.BUSY);
        }
        try {
            var rotation = repositories.prepareRotation(workspace, username, token);
            auth.withAuthorization(actor, workspace, Set.of(Capability.MANAGE_KEYS), () -> {
                repositories.applyRotation(workspace, rotation);
                return null;
            });
        } finally {
            admission.release();
        }
    }

    public Result create(
            AuthPrincipal actor,
            UUID requestId,
            String displayName,
            String slug,
            String repository,
            String username,
            String token) {
        accounts.account(actor);
        if (requestId == null
                || displayName == null
                || displayName.isBlank()
                || displayName.length() > 120
                || slug == null
                || !slug.matches("[a-z0-9][a-z0-9-]{1,62}[a-z0-9]")) {
            throw new IllegalArgumentException("Invalid workspace name or slug");
        }
        var coordinates = RepositoryCoordinates.parse(repository);
        if (!admission.tryAcquire()) {
            throw new RepositoryConnectionException(RepositoryConnectionException.Code.BUSY);
        }
        try {
            UUID lease = UUID.randomUUID();
            Attempt attempt = accounts.withAccount(actor, () -> {
                // Serializes the initial insert as well as retries for one account/request pair.
                var previous = find(actor, requestId, true);
                if (previous != null) {
                    if (!previous.name().equals(displayName.strip())
                            || !previous.slug().equals(slug)
                            || !previous.uri().equals(coordinates.canonicalUri())) {
                        throw new IllegalArgumentException("Request ID belongs to different workspace details");
                    }
                    if (previous.stage().equals("READY")
                            || (previous.stage().equals("VALIDATING")
                                    && previous.updated()
                                            .toInstant()
                                            .plus(Duration.ofMinutes(5))
                                            .isAfter(clock.instant()))) {
                        return previous;
                    }
                }
                WorkspaceId id = previous == null ? WorkspaceId.random() : previous.workspace();
                byte[] sealed = previous != null && username == null && token == null
                        ? previous.sealed()
                        : repositories.seal(id, coordinates, username, token);
                jdbc.update(
                        """
                        insert into space_creation_attempts(account_id,request_id,workspace_id,display_name,public_slug,canonical_uri,sealed_credentials,stage,lease_id,updated_at)
                        values (?,?,?,?,?,?,?,'VALIDATING',?,?)
                        on conflict(account_id,request_id) do update set sealed_credentials=excluded.sealed_credentials,stage='VALIDATING',failure_code=null,lease_id=excluded.lease_id,updated_at=excluded.updated_at
                        """,
                        actor.accountId(),
                        requestId,
                        id.value(),
                        displayName.strip(),
                        slug,
                        coordinates.canonicalUri(),
                        sealed,
                        lease,
                        Timestamp.from(clock.instant()));
                return find(actor, requestId, false);
            });
            if (!attempt.lease().equals(lease)) {
                return result(attempt);
            }
            try {
                var verified = repositories.verify(attempt.workspace(), coordinates, attempt.sealed());
                return transactions.execute(status -> {
                    Attempt current = find(actor, requestId, true);
                    if (!current.lease().equals(lease)) {
                        return result(current);
                    }
                    accounts.account(actor);
                    workspaces.create(attempt.workspace(), attempt.name(), attempt.slug());
                    auth.establishWorkspaceOwner(actor, attempt.workspace());
                    repositories.install(attempt.workspace(), coordinates, attempt.sealed(), verified);
                    jdbc.update(
                            "update space_creation_attempts set stage='READY',failure_code=null,sealed_credentials=null,updated_at=? where account_id=? and request_id=? and lease_id=?",
                            Timestamp.from(clock.instant()),
                            actor.accountId(),
                            requestId,
                            lease);
                    return result(find(actor, requestId, false));
                });
            } catch (RuntimeException failure) {
                String code = failure instanceof RepositoryConnectionException connection
                        ? connection.code().name()
                        : failure instanceof DataIntegrityViolationException ? "DUPLICATE" : "UNAVAILABLE";
                // No exception text or token becomes a persisted diagnostic or HTTP result.
                jdbc.update(
                        "update space_creation_attempts set stage='FAILED',failure_code=?,updated_at=? where account_id=? and request_id=? and lease_id=? and stage='VALIDATING'",
                        code,
                        Timestamp.from(clock.instant()),
                        actor.accountId(),
                        requestId,
                        lease);
                return status(actor, requestId);
            }
        } finally {
            admission.release();
        }
    }

    public Result status(AuthPrincipal actor, UUID requestId) {
        accounts.account(actor);
        Attempt value = find(actor, requestId, false);
        if (value == null) {
            throw new AuthException(AuthException.Code.DENIED);
        }
        return result(value);
    }

    private Attempt find(AuthPrincipal actor, UUID id, boolean lock) {
        List<Attempt> values = jdbc.query(
                "select workspace_id,display_name,public_slug,canonical_uri,sealed_credentials,stage,failure_code,lease_id,updated_at from space_creation_attempts where account_id=? and request_id=?"
                        + (lock ? " for update" : ""),
                (row, number) -> new Attempt(
                        new WorkspaceId(row.getObject(1, UUID.class)),
                        row.getString(2),
                        row.getString(3),
                        row.getString(4),
                        row.getBytes(5),
                        row.getString(6),
                        row.getString(7),
                        row.getObject(8, UUID.class),
                        row.getTimestamp(9)),
                actor.accountId(),
                id);
        return values.isEmpty() ? null : values.getFirst();
    }

    private Result result(Attempt value) {
        long remaining = value.stage().equals("VALIDATING")
                ? Math.max(
                        0,
                        Duration.between(
                                        clock.instant(),
                                        value.updated().toInstant().plus(Duration.ofMinutes(5)))
                                .toMillis())
                : 0;
        return new Result(value.workspace().toString(), value.stage(), value.failure(), (int)
                Math.min(300, (remaining + 999) / 1000));
    }

    public record Result(String workspaceId, String stage, String failureCode, int retryAfterSeconds) {}

    private record Attempt(
            WorkspaceId workspace,
            String name,
            String slug,
            String uri,
            byte[] sealed,
            String stage,
            String failure,
            UUID lease,
            Timestamp updated) {
        @Override
        public String toString() {
            return "SpaceCreationAttempt[redacted]";
        }
    }
}
