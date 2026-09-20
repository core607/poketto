package io.github.core607.poketto.auth;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Provider subjects own identities; email equality never links an existing account. */
public final class GoogleAccounts {
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final Accounts accounts;
    private final EmailAccounts emails;
    private final TransactionTemplate transactions;

    public GoogleAccounts(
            JdbcTemplate jdbc,
            AuthService auth,
            Accounts accounts,
            EmailAccounts emails,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.accounts = accounts;
        this.emails = emails;
        this.transactions = new TransactionTemplate(manager);
    }

    public AuthPrincipal authenticate(Identity identity) {
        return mutate(() -> {
            List<UUID> linked = linked(identity.subject());
            if (!linked.isEmpty()) {
                AuthPrincipal principal = lockIdentity(linked.getFirst());
                jdbc.update(
                        "update auth_external_identities set email=? where provider='GOOGLE' and subject=?",
                        identity.email(),
                        identity.subject());
                return principal;
            }
            emails.requireUnusedEmail(identity.email(), null);
            UUID id = auth.createAccount("user-" + UUID.randomUUID(), null, false);
            jdbc.update(
                    "update auth_accounts set verified_email=?,display_name=? where account_id=?",
                    identity.email(),
                    identity.displayName(),
                    id);
            insert(identity, id);
            return AuthService.accountPrincipal(id);
        });
    }

    public AuthPrincipal link(AuthPrincipal actor, Identity identity) {
        return mutate(() -> accounts.withAccount(actor, () -> {
            List<UUID> linked = linked(identity.subject());
            if (!linked.isEmpty() && !linked.getFirst().equals(actor.accountId())) {
                throw new AuthException(AuthException.Code.IDENTITY_IN_USE);
            }
            List<String> subjects = jdbc.query(
                    "select subject from auth_external_identities where provider='GOOGLE' and account_id=?",
                    (row, number) -> row.getString(1),
                    actor.accountId());
            if (!subjects.isEmpty() && !subjects.getFirst().equals(identity.subject())) {
                throw new AuthException(AuthException.Code.IDENTITY_IN_USE);
            }
            emails.requireUnusedEmail(identity.email(), actor.accountId());
            if (linked.isEmpty()) {
                insert(identity, actor.accountId());
            }
            jdbc.update(
                    "update auth_accounts set verified_email=coalesce(verified_email,?) where account_id=?",
                    identity.email(),
                    actor.accountId());
            return actor;
        }));
    }

    public EmailAccounts.Profile unlink(AuthPrincipal actor) {
        return mutate(() -> accounts.withAccount(actor, () -> {
            Boolean password = jdbc.queryForObject(
                    "select password_hash is not null from auth_accounts where account_id=?",
                    Boolean.class,
                    actor.accountId());
            if (!Boolean.TRUE.equals(password)) {
                throw new AuthException(AuthException.Code.LAST_LOGIN_METHOD);
            }
            jdbc.update(
                    "delete from auth_external_identities where provider='GOOGLE' and account_id=?", actor.accountId());
            return emails.profile(actor);
        }));
    }

    private List<UUID> linked(String subject) {
        return jdbc.query(
                "select account_id from auth_external_identities where provider='GOOGLE' and subject=?",
                (row, number) -> row.getObject(1, UUID.class),
                subject);
    }

    private AuthPrincipal lockIdentity(UUID account) {
        return jdbc.queryForObject(
                "select credential_version from auth_accounts where account_id=? for update",
                (row, number) -> new AuthPrincipal(AuthPrincipal.Kind.ACCOUNT, account, account, row.getLong(1)),
                account);
    }

    private void insert(Identity identity, UUID account) {
        jdbc.update(
                "insert into auth_external_identities(provider,subject,account_id,email) values ('GOOGLE',?,?,?)",
                identity.subject(),
                account,
                identity.email());
    }

    private <T> T mutate(Supplier<T> action) {
        try {
            return transactions.execute(status -> {
                // Serializes provider login/link/unlink decisions before locking any account, including races with
                // site-policy decisions. Token exchange and signature verification happen before this boundary.
                jdbc.queryForObject(
                        "select singleton from auth_initialization where singleton=true for update", Boolean.class);
                return action.get();
            });
        } catch (DataIntegrityViolationException conflict) {
            throw new AuthException(AuthException.Code.IDENTITY_IN_USE, conflict);
        }
    }

    /** Accepted only from an adapter that verified the ID token and its email_verified claim. */
    public record Identity(String subject, String email, String displayName) {
        public Identity {
            if (subject == null
                    || subject.isBlank()
                    || subject.length() > 255
                    || subject.codePoints().anyMatch(Character::isISOControl)) {
                throw new AuthException(AuthException.Code.INVALID_CREDENTIALS);
            }
            email = EmailAddress.normalize(email);
            displayName = EmailAccounts.displayName(
                    displayName == null || displayName.isBlank()
                            ? email.substring(0, email.indexOf('@'))
                            : displayName);
        }

        @Override
        public String toString() {
            return "GoogleIdentity[REDACTED]";
        }
    }
}
