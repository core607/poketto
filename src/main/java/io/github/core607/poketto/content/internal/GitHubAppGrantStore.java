package io.github.core607.poketto.content.internal;

import static io.github.core607.poketto.content.GitHubConnectionException.Code.AUTHORIZATION_CHANGED;

import io.github.core607.poketto.content.GitHubConnectionException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Versioned grant writes and refresh leases; no provider I/O or account policy belongs in this store. */
final class GitHubAppGrantStore {
    private final JdbcTemplate jdbc;
    private final GitHubAppGrantCipher cipher;
    private final String clientId;
    private final Clock clock;

    GitHubAppGrantStore(JdbcTemplate jdbc, GitHubAppGrantCipher cipher, String clientId, Clock clock) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.clientId = clientId;
        this.clock = clock;
    }

    Optional<Grant> find(UUID account) {
        return jdbc
                .query("select * from content_github_grants where account_id=?", GitHubAppGrantStore::row, account)
                .stream()
                .findFirst();
    }

    void lockActive(UUID account, long version, long owner) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("GitHub grant locking requires an account transaction");
        }
        List<Long> rows = jdbc.query(
                "select version from content_github_grants where account_id=? and version=? and github_user_id=? and client_id=? and state='ACTIVE' for update",
                (row, number) -> row.getLong(1),
                account,
                version,
                owner,
                clientId);
        if (rows.isEmpty()) {
            throw new GitHubConnectionException(AUTHORIZATION_CHANGED);
        }
    }

    Grant authorize(
            UUID account, long expectedVersion, GitHubAppRepositories.Owner owner, GitHubAppOAuth.Tokens tokens) {
        GitHubAppRepositories.requirePersonal(owner);
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE) {
            throw new IllegalArgumentException("GitHub authorization version is invalid");
        }
        long version = expectedVersion + 1;
        byte[] sealed = cipher.seal(account, owner.id(), version, tokens);
        List<Grant> rows = jdbc.query(
                """
                insert into content_github_grants(account_id,client_id,github_user_id,github_login,version,state,
                    sealed_tokens,access_expires_at,refresh_expires_at,updated_at)
                select ?,?,?,?,?,'ACTIVE',?,?,?,? where ?=0
                on conflict(account_id) do nothing returning *
                """,
                GitHubAppGrantStore::row,
                account,
                clientId,
                owner.id(),
                owner.login(),
                version,
                sealed,
                timestamp(tokens.accessExpiresAt()),
                timestamp(tokens.refreshExpiresAt()),
                timestamp(clock.instant()),
                expectedVersion);
        if (rows.isEmpty() && expectedVersion > 0) {
            rows = jdbc.query(
                    """
                    update content_github_grants set client_id=?,github_login=?,version=?,state='ACTIVE',sealed_tokens=?,
                        access_expires_at=?,refresh_expires_at=?,refresh_lease=null,refresh_deadline=null,updated_at=?
                    where account_id=? and version=? and github_user_id=? returning *
                    """,
                    GitHubAppGrantStore::row,
                    clientId,
                    owner.login(),
                    version,
                    sealed,
                    timestamp(tokens.accessExpiresAt()),
                    timestamp(tokens.refreshExpiresAt()),
                    timestamp(clock.instant()),
                    account,
                    expectedVersion,
                    owner.id());
        }
        return changed(rows);
    }

    /** Claims one refresh without holding a database transaction across the provider exchange. */
    Optional<Grant> claimRefresh(Grant grant) {
        UUID lease = UUID.randomUUID();
        Instant now = clock.instant();
        return jdbc
                .query(
                        """
                update content_github_grants set state='REFRESHING',refresh_lease=?,refresh_deadline=?,updated_at=?
                where account_id=? and version=? and client_id=? and state='ACTIVE' returning *
                """,
                        GitHubAppGrantStore::row,
                        lease,
                        timestamp(now.plusSeconds(60)),
                        timestamp(now),
                        grant.account(),
                        grant.version(),
                        clientId)
                .stream()
                .findFirst();
    }

    Grant refreshed(Grant lease, GitHubAppOAuth.Tokens tokens) {
        long version = Math.addExact(lease.version(), 1);
        byte[] sealed = cipher.seal(lease.account(), lease.ownerId(), version, tokens);
        return changed(jdbc.query(
                """
                update content_github_grants set version=?,state='ACTIVE',sealed_tokens=?,access_expires_at=?,
                    refresh_expires_at=?,refresh_lease=null,refresh_deadline=null,updated_at=?
                where account_id=? and client_id=? and version=? and state='REFRESHING'
                    and refresh_lease=? and refresh_deadline>? returning *
                """,
                GitHubAppGrantStore::row,
                version,
                sealed,
                timestamp(tokens.accessExpiresAt()),
                timestamp(tokens.refreshExpiresAt()),
                timestamp(clock.instant()),
                lease.account(),
                clientId,
                lease.version(),
                lease.refreshLease(),
                timestamp(clock.instant())));
    }

    /** Safe only when the transport rejected admission before sending any provider request. */
    void releaseRefresh(Grant lease) {
        jdbc.update("""
                update content_github_grants set state='ACTIVE',refresh_lease=null,refresh_deadline=null,updated_at=?
                where account_id=? and client_id=? and version=? and state='REFRESHING' and refresh_lease=?
                """, timestamp(clock.instant()), lease.account(), clientId, lease.version(), lease.refreshLease());
    }

    void failedRefresh(Grant lease) {
        jdbc.update("""
                update content_github_grants set version=version+1,state='REAUTHORIZATION',sealed_tokens=null,
                    access_expires_at=null,refresh_expires_at=null,refresh_lease=null,refresh_deadline=null,updated_at=?
                where account_id=? and client_id=? and version=? and state='REFRESHING' and refresh_lease=?
                """, timestamp(clock.instant()), lease.account(), clientId, lease.version(), lease.refreshLease());
    }

    /** An expired refresh may have consumed the old refresh token; it must not be retried. */
    void expireRefresh(UUID account) {
        jdbc.update("""
                update content_github_grants set version=version+1,state='REAUTHORIZATION',sealed_tokens=null,
                    access_expires_at=null,refresh_expires_at=null,refresh_lease=null,refresh_deadline=null,updated_at=?
                where account_id=? and state='REFRESHING' and refresh_deadline<=?
                """, timestamp(clock.instant()), account, timestamp(clock.instant()));
    }

    void revoke(UUID account, long version) {
        int count = jdbc.update("""
                update content_github_grants set version=version+1,state='REVOKED',sealed_tokens=null,
                    access_expires_at=null,refresh_expires_at=null,refresh_lease=null,refresh_deadline=null,updated_at=?
                where account_id=? and version=?
                """, timestamp(clock.instant()), account, version);
        if (count != 1) {
            throw new GitHubConnectionException(AUTHORIZATION_CHANGED);
        }
    }

    GitHubAppOAuth.Tokens tokens(Grant grant) {
        if (!matchesConfiguredApp(grant)) {
            throw new GitHubConnectionException(GitHubConnectionException.Code.AUTHORIZATION_REQUIRED);
        }
        if (grant.sealedTokens() == null) {
            throw new GitHubConnectionException(GitHubConnectionException.Code.AUTHORIZATION_REQUIRED);
        }
        return cipher.open(grant.account(), grant.ownerId(), grant.version(), grant.sealedTokens());
    }

    boolean matchesConfiguredApp(Grant grant) {
        return clientId.equals(grant.clientId());
    }

    private static Grant changed(List<Grant> rows) {
        if (rows.size() != 1) {
            throw new GitHubConnectionException(AUTHORIZATION_CHANGED);
        }
        return rows.getFirst();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet row, String name) throws SQLException {
        Timestamp value = row.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private static Grant row(ResultSet row, int index) throws SQLException {
        return new Grant(
                row.getObject("account_id", UUID.class),
                row.getString("client_id"),
                row.getLong("github_user_id"),
                row.getString("github_login"),
                row.getLong("version"),
                State.valueOf(row.getString("state")),
                row.getBytes("sealed_tokens"),
                instant(row, "access_expires_at"),
                instant(row, "refresh_expires_at"),
                row.getObject("refresh_lease", UUID.class),
                instant(row, "refresh_deadline"));
    }

    enum State {
        ACTIVE,
        REFRESHING,
        REAUTHORIZATION,
        REVOKED
    }

    record Grant(
            UUID account,
            String clientId,
            long ownerId,
            String login,
            long version,
            State state,
            byte[] sealedTokens,
            Instant accessExpiresAt,
            Instant refreshExpiresAt,
            UUID refreshLease,
            Instant refreshDeadline) {
        Grant {
            sealedTokens = sealedTokens == null ? null : sealedTokens.clone();
        }

        @Override
        public byte[] sealedTokens() {
            return sealedTokens == null ? null : sealedTokens.clone();
        }

        @Override
        public String toString() {
            return "GitHubGrant[version=" + version + ",state=" + state + "]";
        }
    }
}
