package io.github.core607.poketto.auth;

import static io.github.core607.poketto.auth.AuthException.Code.DENIED;
import static io.github.core607.poketto.auth.AuthException.Code.INVALID_INPUT;
import static io.github.core607.poketto.auth.AuthException.Code.INVALID_INVITATION;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Registration invitations establish accounts, never workspace membership. */
public final class RegistrationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AuthService auth;
    private final RegistrationInvitationPolicy policy;
    private final Clock clock;

    public RegistrationService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            AuthService auth,
            RegistrationInvitationPolicy policy,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.auth = auth;
        this.policy = policy;
        this.clock = clock;
    }

    public AccountIdentity account(AuthPrincipal actor) {
        return account(actor, false);
    }

    /** Serializes an account-level operation with other account mutations in the same transaction. */
    public <T> T withAccount(AuthPrincipal actor, Supplier<T> operation) {
        return transactions.execute(status -> {
            account(actor, true);
            return operation.get();
        });
    }

    private AccountIdentity account(AuthPrincipal actor, boolean lock) {
        if (actor == null || actor.kind() != AuthPrincipal.Kind.ACCOUNT) {
            throw new AuthException(DENIED);
        }
        var identities = jdbc.query(
                "select account_id, login_name, instance_admin from auth_accounts where account_id=?"
                        + (lock ? " for update" : ""),
                (row, number) -> new AccountIdentity(row.getObject(1, UUID.class), row.getString(2), row.getBoolean(3)),
                actor.accountId());
        if (identities.isEmpty()) {
            throw new AuthException(DENIED);
        }
        return identities.getFirst();
    }

    public boolean mayIssue(AuthPrincipal actor) {
        return policy.mayIssue(account(actor));
    }

    public IssuedToken issue(AuthPrincipal actor) {
        return transactions.execute(status -> {
            // Serialize issuance per account so an allowance policy can count and reserve atomically.
            AccountIdentity issuer = account(actor, true);
            if (!policy.mayIssue(issuer)) {
                throw new AuthException(DENIED);
            }
            String token = auth.randomToken("registration_");
            UUID id = UUID.randomUUID();
            Instant now = clock.instant();
            jdbc.update(
                    "insert into auth_registration_invitations "
                            + "(invitation_id,token_digest,created_by,created_at,expires_at) values (?,?,?,?,?)",
                    id,
                    AuthService.digestCredential(token),
                    issuer.accountId(),
                    Timestamp.from(now),
                    Timestamp.from(now.plus(Duration.ofDays(7))));
            return new IssuedToken(id, token);
        });
    }

    public AuthPrincipal register(String token, String login, String password) {
        String digest = AuthService.digestCredential(token);
        // Invalid tokens never trigger password hashing or reveal username availability.
        invitation(digest, false);
        String normalized = auth.loginName(login);
        String encoded = auth.encodePassword(password);
        return transactions.execute(status -> {
            Invitation invitation = invitation(digest, true);
            UUID account = auth.createAccount(normalized, encoded, false);
            jdbc.update(
                    "update auth_registration_invitations set used_at=?,used_by=? where invitation_id=?",
                    Timestamp.from(clock.instant()),
                    account,
                    invitation.id());
            return AuthService.accountPrincipal(account);
        });
    }

    private Invitation invitation(String digest, boolean lock) {
        List<Invitation> values = jdbc.query(
                "select invitation_id,expires_at,revoked_at is not null,used_at is not null "
                        + "from auth_registration_invitations where token_digest=?" + (lock ? " for update" : ""),
                (row, number) -> new Invitation(
                        row.getObject(1, UUID.class),
                        row.getTimestamp(2).toInstant(),
                        row.getBoolean(3),
                        row.getBoolean(4)),
                digest);
        if (values.isEmpty()) {
            throw new AuthException(INVALID_INVITATION);
        }
        Invitation value = values.getFirst();
        if (value.revoked() || value.used() || !clock.instant().isBefore(value.expiresAt())) {
            throw new AuthException(INVALID_INVITATION);
        }
        return value;
    }

    public AuthService.Page<AuthService.InvitationInfo> invitations(AuthPrincipal actor, int offset, int limit) {
        UUID issuer = account(actor).accountId();
        if (offset < 0 || offset > 100_000 || limit < 1 || limit > 100) {
            throw new AuthException(INVALID_INPUT);
        }
        var values = jdbc.query(
                "select invitation_id,expires_at,revoked_at is not null,used_at is not null "
                        + "from auth_registration_invitations where created_by=? "
                        + "order by created_at desc,invitation_id limit ? offset ?",
                (row, number) -> new AuthService.InvitationInfo(
                        row.getObject(1, UUID.class),
                        row.getTimestamp(2).toInstant(),
                        row.getBoolean(3),
                        row.getBoolean(4)),
                issuer,
                limit,
                offset);
        long total = jdbc.queryForObject(
                "select count(*) from auth_registration_invitations where created_by=?", Long.class, issuer);
        return new AuthService.Page<>(values, total, offset, limit);
    }

    public void revoke(AuthPrincipal actor, UUID id) {
        UUID issuer = account(actor).accountId();
        jdbc.update(
                "update auth_registration_invitations set revoked_at=? "
                        + "where invitation_id=? and created_by=? and revoked_at is null and used_at is null",
                Timestamp.from(clock.instant()),
                id,
                issuer);
    }

    private record Invitation(UUID id, Instant expiresAt, boolean revoked, boolean used) {}
}
