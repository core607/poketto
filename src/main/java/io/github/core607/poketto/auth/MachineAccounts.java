package io.github.core607.poketto.auth;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Account-first lock ordering fences group changes and credential recovery for machine account actions. */
public final class MachineAccounts {
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final TransactionTemplate transactions;

    public MachineAccounts(JdbcTemplate jdbc, AuthService auth, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.transactions = new TransactionTemplate(manager);
    }

    public <T> T withCreator(AuthPrincipal actor, WorkspaceId workspace, Function<Identity, T> operation) {
        return transactions.execute(status -> operation.apply(lock(actor, workspace)));
    }

    /** Caller owns the transaction; take policy, account and credential-workspace guards before publication locks. */
    public Identity lock(AuthPrincipal actor, WorkspaceId workspace) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Machine account authority requires a transaction");
        }
        if (actor == null || actor.kind() != AuthPrincipal.Kind.API_KEY) {
            throw new AuthException(AuthException.Code.DENIED);
        }
        lockPolicy();
        AccountIdentity account = lockCreator(actor.accountId());
        lockWorkspace(workspace);
        auth.authorize(actor, workspace);
        return new Identity(
                account.accountId(), account.displayName(), personal(actor.subjectId(), account.accountId()));
    }

    private void lockPolicy() {
        jdbc.queryForObject("select singleton from auth_initialization where singleton=true for share", Boolean.class);
    }

    private AccountIdentity lockCreator(UUID account) {
        List<AccountIdentity> rows = jdbc.query(
                "select account_id,login_name,display_name,site_group from auth_accounts where account_id=? for update",
                (row, number) -> new AccountIdentity(
                        row.getObject(1, UUID.class),
                        row.getString(2),
                        row.getString(3),
                        SiteGroup.valueOf(row.getString(4))),
                account);
        if (rows.isEmpty() || !rows.getFirst().group().mayCreateSpace()) {
            throw new AuthException(AuthException.Code.DENIED);
        }
        return rows.getFirst();
    }

    private void lockWorkspace(WorkspaceId workspace) {
        if (jdbc.query(
                        "select workspace_id from workspaces where workspace_id=? for share",
                        (row, number) -> row.getObject(1),
                        workspace.value())
                .isEmpty()) {
            throw new AuthException(AuthException.Code.DENIED);
        }
    }

    /** A key acts for its holder's account only when the holder issued it, directly, by one of their keys or by OAuth. */
    private boolean personal(UUID key, UUID account) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from auth_api_keys where key_id=? and account_id=? and created_by=account_id)",
                Boolean.class,
                key,
                account));
    }

    /** {@code personal}: the key's holder issued it, so it may act for the holder's account. */
    public record Identity(UUID accountId, String displayName, boolean personal) {}
}
