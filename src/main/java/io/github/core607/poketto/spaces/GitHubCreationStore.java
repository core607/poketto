package io.github.core607.poketto.spaces;

import io.github.core607.poketto.content.GitHubConnectionException;
import io.github.core607.poketto.content.GitHubRepositoryProvisioning;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable remote creation intent. Result recording cannot grant space membership or publish content. */
final class GitHubCreationStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    GitHubCreationStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    Optional<Attempt> find(UUID account, UUID request) {
        return jdbc
                .query(
                        "select * from space_github_creation_attempts where account_id=? and request_id=?",
                        GitHubCreationStore::row,
                        account,
                        request)
                .stream()
                .findFirst();
    }

    Attempt claim(
            UUID account, GitHubSpaceCreation.Request request, GitHubRepositoryProvisioning.Owner owner, UUID lease) {
        requireTransaction();
        Optional<Attempt> previous = find(account, request.requestId());
        if (previous.isPresent()) {
            Attempt old = previous.orElseThrow();
            old.requireSame(request);
            if (old.finished() || old.blocksClaim(clock.instant())) {
                return old;
            }
            jdbc.update(
                    """
                    update space_github_creation_attempts set stage=case when creation_requested then 'UNCERTAIN' else 'PREPARING' end,
                        grant_version=?,failure_code=null,lease_id=?,lease_started_at=?,lease_expires_at=?,updated_at=?
                    where account_id=? and request_id=?
                    """,
                    owner.grantVersion(),
                    lease,
                    timestamp(clock.instant()),
                    timestamp(clock.instant().plusSeconds(300)),
                    timestamp(clock.instant()),
                    account,
                    request.requestId());
        } else {
            Instant now = clock.instant();
            jdbc.update(
                    """
                    insert into space_github_creation_attempts(account_id,request_id,workspace_id,display_name,public_slug,
                        github_owner_id,repository_name,creation_marker,grant_version,stage,lease_id,lease_started_at,lease_expires_at,updated_at)
                    values (?,?,?,?,?,?,?,?,?,'PREPARING',?,?,?,?)
                    """,
                    account,
                    request.requestId(),
                    WorkspaceId.random().value(),
                    request.displayName(),
                    request.slug(),
                    owner.id(),
                    request.repositoryName(),
                    UUID.randomUUID(),
                    owner.grantVersion(),
                    lease,
                    timestamp(now),
                    timestamp(now.plusSeconds(300)),
                    timestamp(now));
        }
        return required(account, request.requestId());
    }

    void beginCreate(Attempt attempt) {
        requireTransaction();
        Instant now = clock.instant();
        int changed = jdbc.update(
                """
                update space_github_creation_attempts set stage='CREATING',creation_requested=true,updated_at=?
                where account_id=? and request_id=? and lease_id=? and not creation_requested
                    and stage='PREPARING' and lease_started_at<=? and lease_expires_at>?
                """,
                timestamp(now),
                attempt.account(),
                attempt.request(),
                attempt.lease(),
                timestamp(now),
                timestamp(now));
        if (changed != 1) {
            throw new GitHubConnectionException(GitHubConnectionException.Code.AUTHORIZATION_CHANGED);
        }
    }

    Attempt recordRepository(Attempt attempt, GitHubRepositoryProvisioning.Repository repository) {
        if (repository.ownerId() != attempt.owner() || !repository.name().equalsIgnoreCase(attempt.repositoryName())) {
            throw new GitHubConnectionException(GitHubConnectionException.Code.REPOSITORY_CHANGED);
        }
        Instant now = clock.instant();
        // Keep the remote fact even after a downgrade. This writes neither a workspace nor a member.
        jdbc.update(
                """
                update space_github_creation_attempts set stage='AWAITING_INSTALLATION',creation_requested=true,
                    repository_id=?,canonical_uri=?,failure_code=null,lease_id=null,lease_started_at=null,lease_expires_at=null,updated_at=?
                where account_id=? and request_id=? and lease_id=? and creation_requested
                    and lease_started_at<=? and lease_expires_at>? and repository_id is null
                """,
                repository.id(),
                repository.canonicalUri(),
                timestamp(now),
                attempt.account(),
                attempt.request(),
                attempt.lease(),
                timestamp(now),
                timestamp(now));
        return required(attempt.account(), attempt.request());
    }

    Attempt failed(Attempt attempt, String code, boolean definitelyNotCreated, GitHubSpaceCreation.Stage stopped) {
        Instant now = clock.instant();
        jdbc.update(
                """
                update space_github_creation_attempts set stage=case when cast(? as text) is not null then cast(? as text)
                    when creation_requested and not ? then 'UNCERTAIN' else 'PREPARING' end,
                    creation_requested=creation_requested and not ?,failure_code=?,lease_id=null,
                    lease_started_at=null,lease_expires_at=null,updated_at=?
                where account_id=? and request_id=? and lease_id=? and repository_id is null
                    and lease_started_at<=? and lease_expires_at>?
                """,
                stopped == null ? null : stopped.name(),
                stopped == null ? null : stopped.name(),
                definitelyNotCreated,
                definitelyNotCreated,
                code,
                timestamp(now),
                attempt.account(),
                attempt.request(),
                attempt.lease(),
                timestamp(now),
                timestamp(now));
        return required(attempt.account(), attempt.request());
    }

    private Attempt required(UUID account, UUID request) {
        return find(account, request)
                .orElseThrow(() -> new IllegalStateException("GitHub creation attempt is missing"));
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("GitHub creation intent requires an account transaction");
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet row, String name) throws SQLException {
        Timestamp value = row.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private static Attempt row(ResultSet row, int number) throws SQLException {
        return new Attempt(
                row.getObject("account_id", UUID.class),
                row.getObject("request_id", UUID.class),
                new WorkspaceId(row.getObject("workspace_id", UUID.class)),
                row.getString("display_name"),
                row.getString("public_slug"),
                row.getLong("github_owner_id"),
                row.getString("repository_name"),
                row.getObject("creation_marker", UUID.class),
                row.getLong("grant_version"),
                GitHubSpaceCreation.Stage.valueOf(row.getString("stage")),
                row.getBoolean("creation_requested"),
                row.getObject("repository_id", Long.class),
                row.getString("canonical_uri"),
                row.getString("failure_code"),
                row.getObject("lease_id", UUID.class),
                instant(row, "lease_started_at"),
                instant(row, "lease_expires_at"));
    }

    record Attempt(
            UUID account,
            UUID request,
            WorkspaceId workspace,
            String name,
            String slug,
            long owner,
            String repositoryName,
            UUID marker,
            long grantVersion,
            GitHubSpaceCreation.Stage stage,
            boolean creationRequested,
            Long repositoryId,
            String canonicalUri,
            String failure,
            UUID lease,
            Instant leaseStarted,
            Instant leaseExpires) {
        void requireSame(GitHubSpaceCreation.Request input) {
            boolean same = name.equals(input.displayName())
                    && slug.equals(input.slug())
                    && owner == input.githubOwnerId()
                    && repositoryName.equals(input.repositoryName());
            if (!same) {
                throw new IllegalArgumentException("Request ID belongs to different GitHub space details");
            }
        }

        boolean finished() {
            return repositoryId != null || stage == GitHubSpaceCreation.Stage.REJECTED;
        }

        boolean blocksClaim(Instant now) {
            return lease != null && now.isBefore(leaseExpires);
        }
    }
}
