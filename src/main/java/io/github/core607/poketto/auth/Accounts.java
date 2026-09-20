package io.github.core607.poketto.auth;

import static io.github.core607.poketto.auth.AuthException.Code.DENIED;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Account identity and serialization, independent of workspace membership. */
public final class Accounts {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public Accounts(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
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
                "select account_id, login_name, display_name, site_group from auth_accounts where account_id=? and credential_version=?"
                        + (lock ? " for update" : ""),
                (row, number) -> new AccountIdentity(
                        row.getObject(1, UUID.class),
                        row.getString(2),
                        row.getString(3),
                        SiteGroup.valueOf(row.getString(4))),
                actor.accountId(),
                actor.credentialVersion());
        if (identities.isEmpty()) {
            throw new AuthException(DENIED);
        }
        return identities.getFirst();
    }

    /** Creation eligibility is independent of grants on existing workspaces. */
    public void requireCreator(AuthPrincipal actor) {
        if (!account(actor).group().mayCreateSpace()) {
            throw new AuthException(DENIED);
        }
    }
}
