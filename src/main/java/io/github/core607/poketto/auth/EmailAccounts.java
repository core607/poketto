package io.github.core607.poketto.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Email identity operations keep proof consumption and the account mutation in one transaction. */
public final class EmailAccounts {
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final Accounts accounts;
    private final EmailChallenges challenges;
    private final CredentialRevocations revocations;
    private final Clock clock;

    public EmailAccounts(
            JdbcTemplate jdbc, AuthService auth, Accounts accounts, EmailChallenges challenges, Clock clock) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.accounts = accounts;
        this.challenges = challenges;
        this.revocations = new CredentialRevocations(jdbc, auth);
        this.clock = clock;
    }

    public EmailChallenges.Receipt requestSignup(String email, String address) {
        return challenges.send(email, EmailPurpose.SIGNUP, null, address, true);
    }

    public AuthPrincipal signup(EmailChallenges.Proof proof, String password, String displayName) {
        String name = displayName(displayName);
        try {
            return challenges.consume(proof, EmailPurpose.SIGNUP, null, () -> {
                requireUnusedEmail(proof.email(), null);
                UUID id = auth.createAccount("user-" + UUID.randomUUID(), auth.encodePassword(password), false);
                jdbc.update(
                        "update auth_accounts set verified_email=?,display_name=? where account_id=?",
                        proof.email(),
                        name,
                        id);
                return AuthService.accountPrincipal(id);
            });
        } catch (DataIntegrityViolationException conflict) {
            throw new AuthException(AuthException.Code.EMAIL_IN_USE);
        }
    }

    public EmailChallenges.Receipt requestBinding(AuthPrincipal actor, String email, String address) {
        accounts.account(actor);
        return challenges.send(email, EmailPurpose.BIND, actor.accountId(), address, true);
    }

    public Profile bind(AuthPrincipal actor, EmailChallenges.Proof proof) {
        try {
            return challenges.consume(
                    proof,
                    EmailPurpose.BIND,
                    actor.accountId(),
                    () -> accounts.withAccount(actor, () -> {
                        requireUnusedEmail(proof.email(), actor.accountId());
                        jdbc.update(
                                "update auth_accounts set verified_email=? where account_id=?",
                                proof.email(),
                                actor.accountId());
                        return profile(actor);
                    }));
        } catch (DataIntegrityViolationException conflict) {
            throw new AuthException(AuthException.Code.EMAIL_IN_USE);
        }
    }

    /** Every anonymous response acknowledges the request, never confirms account existence or delivery. */
    public EmailChallenges.Receipt requestRecovery(String input, String address) {
        if (!challenges.available()) {
            throw new EmailChallengeException(EmailChallengeException.Code.DELIVERY_UNAVAILABLE);
        }
        String email = EmailAddress.normalize(input);
        List<UUID> ids = jdbc.query(
                "select account_id from auth_accounts where verified_email=?",
                (row, number) -> row.getObject(1, UUID.class),
                email);
        UUID account = ids.isEmpty() ? null : ids.getFirst();
        try {
            return challenges.send(email, EmailPurpose.RECOVERY, account, address, account != null);
        } catch (EmailChallengeException failure) {
            if (failure.code() != EmailChallengeException.Code.DELIVERY_UNAVAILABLE) {
                throw failure;
            }
            // Provider failure must not become an account-existence oracle.
            return new EmailChallenges.Receipt(
                    UUID.randomUUID(), clock.instant().plus(Duration.ofMinutes(10)), 60);
        }
    }

    public void resetPassword(EmailChallenges.Proof proof, String password) {
        List<UUID> targets = jdbc.query(
                "select account_id from auth_accounts where verified_email=?",
                (row, number) -> row.getObject(1, UUID.class),
                proof.email());
        if (targets.isEmpty()) {
            throw new EmailChallengeException(EmailChallengeException.Code.INVALID_CHALLENGE);
        }
        UUID target = targets.getFirst();
        challenges.consume(proof, EmailPurpose.RECOVERY, target, () -> {
            List<UUID> ids = jdbc.query(
                    "select account_id from auth_accounts where account_id=? and verified_email=? for update",
                    (row, number) -> row.getObject(1, UUID.class),
                    target,
                    proof.email());
            if (ids.isEmpty()) {
                throw new EmailChallengeException(EmailChallengeException.Code.INVALID_CHALLENGE);
            }
            UUID id = ids.getFirst();
            jdbc.update(
                    "update auth_accounts set password_hash=?,credential_version=credential_version+1 where account_id=?",
                    auth.encodePassword(password),
                    id);
            revocations.revoke(id);
            return null;
        });
    }

    public Profile profile(AuthPrincipal actor) {
        accounts.account(actor);
        return jdbc.queryForObject(
                "select display_name,verified_email,password_hash is not null,"
                        + "(select email from auth_external_identities where provider='GOOGLE' and account_id=a.account_id) "
                        + "from auth_accounts a where account_id=?",
                (row, number) -> new Profile(row.getString(1), row.getString(2), row.getBoolean(3), row.getString(4)),
                actor.accountId());
    }

    public Profile rename(AuthPrincipal actor, String input) {
        String name = displayName(input);
        return accounts.withAccount(actor, () -> {
            jdbc.update("update auth_accounts set display_name=? where account_id=?", name, actor.accountId());
            return profile(actor);
        });
    }

    void requireUnusedEmail(String email, UUID account) {
        Boolean used = jdbc.queryForObject(
                "select exists(select 1 from auth_accounts where verified_email=? and account_id is distinct from ?)",
                Boolean.class,
                email,
                account);
        if (Boolean.TRUE.equals(used)) {
            throw new AuthException(AuthException.Code.EMAIL_IN_USE);
        }
    }

    static String displayName(String input) {
        if (input == null) {
            throw new AuthException(AuthException.Code.INVALID_INPUT);
        }
        String name = input.strip();
        if (name.isEmpty()
                || name.codePointCount(0, name.length()) > 120
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new AuthException(AuthException.Code.INVALID_INPUT);
        }
        return name;
    }

    public record Profile(String displayName, String email, boolean passwordEnabled, String googleEmail) {}
}
