package io.github.core607.poketto.auth;

import static io.github.core607.poketto.auth.AuthException.Code.INVALID_CREDENTIALS;
import static io.github.core607.poketto.auth.AuthService.failure;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Password authentication captures the credential version that justified the browser identity. */
final class AccountPasswords {
    private final AuthService auth;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final String dummyPasswordHash;

    AccountPasswords(AuthService auth, JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.auth = auth;
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.dummyPasswordHash = passwords.encode(CredentialTokens.random("dummy_"));
    }

    /** Uniform credential rejection includes missing accounts; the HTTP caller must also throttle attempts. */
    public AuthPrincipal authenticate(String login, String password) {
        if (password == null || password.length() > 256) {
            AuditRecords.refused("password.authentication", INVALID_CREDENTIALS.name());
            throw failure(INVALID_CREDENTIALS);
        }
        String normalized;
        try {
            normalized = login != null && login.contains("@") ? EmailAddress.normalize(login) : auth.loginName(login);
        } catch (AuthException | EmailChallengeException exception) {
            normalized = "";
        }
        List<AccountCredential> accounts = jdbc.query(
                "select account_id, password_hash, credential_version from auth_accounts where login_name = ? or verified_email = ?",
                (rs, row) -> new AccountCredential(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3)),
                normalized,
                normalized);
        String encoded = accounts.isEmpty() || accounts.getFirst().hash() == null
                ? dummyPasswordHash
                : accounts.getFirst().hash();
        if (!passwords.matches(password, encoded)
                || accounts.isEmpty()
                || accounts.getFirst().hash() == null) {
            AuditRecords.refused("password.authentication", INVALID_CREDENTIALS.name());
            throw failure(INVALID_CREDENTIALS);
        }
        AccountCredential account = accounts.getFirst();
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "select suspended_at is not null from auth_accounts where account_id=?",
                Boolean.class,
                account.id()))) {
            AuditRecords.refused("password.authentication", AuthException.Code.ACCOUNT_SUSPENDED.name());
            throw failure(AuthException.Code.ACCOUNT_SUSPENDED);
        }
        if (passwords.upgradeEncoding(encoded)) {
            jdbc.update(
                    "update auth_accounts set password_hash = ? where account_id = ? and password_hash = ?",
                    passwords.encode(password),
                    account.id(),
                    encoded);
        }
        AuthPrincipal principal =
                new AuthPrincipal(AuthPrincipal.Kind.ACCOUNT, account.id(), account.id(), account.version());
        validate(principal);
        AuditRecords.authenticated("password.authentication", principal);
        return principal;
    }

    void validate(AuthPrincipal actor) {
        if (actor == null || actor.kind() != AuthPrincipal.Kind.ACCOUNT) {
            throw failure(INVALID_CREDENTIALS);
        }
        Boolean valid = jdbc.queryForObject(
                "select exists(select 1 from auth_accounts where account_id=? and credential_version=? and suspended_at is null)",
                Boolean.class,
                actor.accountId(),
                actor.credentialVersion());
        if (!Boolean.TRUE.equals(valid)) {
            throw failure(INVALID_CREDENTIALS);
        }
    }

    private record AccountCredential(UUID id, String hash, long version) {}
}
