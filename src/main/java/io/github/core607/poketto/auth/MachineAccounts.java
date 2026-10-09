package io.github.core607.poketto.auth;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Account-first lock ordering fences group changes, credential recovery and personal grants. */
public final class MachineAccounts {
    private final JdbcTemplate jdbc;
    private final Accounts accounts;
    private final AuthService auth;
    private final TransactionTemplate transactions;

    public MachineAccounts(JdbcTemplate jdbc, Accounts accounts, AuthService auth, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.auth = auth;
        this.transactions = new TransactionTemplate(manager);
    }

    public <T> T withCreator(AuthPrincipal actor, WorkspaceId workspace, Function<Identity, T> operation) {
        if (actor == null || actor.kind() != AuthPrincipal.Kind.API_KEY) {
            throw new AuthException(AuthException.Code.DENIED);
        }
        return transactions.execute(status -> {
            lockPolicy();
            AccountIdentity account = lockCreator(actor.accountId());
            lockWorkspace(workspace);
            auth.authorize(actor, workspace);
            var identity = new Identity(
                    account.accountId(), account.displayName(), grants(actor.subjectId(), account.accountId()));
            return operation.apply(identity);
        });
    }

    public ConnectionPage connections(AuthPrincipal actor, int offset) {
        if (offset < 0 || offset > 10000) {
            throw new IllegalArgumentException("Connection offset is out of bounds");
        }
        return human(actor, () -> {
            List<Connection> items = jdbc.query(
                    "select k.key_id,w.display_name,coalesce(cl.client_name,'API key') as client_name "
                            + "from auth_api_keys k join workspaces w using(workspace_id) "
                            + "left join oauth_connections c using(key_id) left join oauth_clients cl using(client_id) "
                            + "where k.account_id=? and k.revoked_at is null "
                            + "and (c.key_id is null or c.expires_at>current_timestamp) order by k.key_id limit 101 offset ?",
                    (row, number) -> new Connection(
                            row.getObject(1, UUID.class),
                            row.getString(2),
                            row.getString(3),
                            grants(row.getObject(1, UUID.class), actor.accountId())),
                    actor.accountId(),
                    offset);
            return new ConnectionPage(items.stream().limit(100).toList(), items.size() > 100 ? offset + 100 : null);
        });
    }

    public Set<MachinePermission> set(AuthPrincipal actor, UUID key, Set<MachinePermission> permissions) {
        Set<MachinePermission> selected = Set.copyOf(permissions);
        return human(actor, () -> {
            List<WorkspaceId> targets = jdbc.query(
                    "select workspace_id from auth_api_keys where key_id=? and account_id=? and revoked_at is null",
                    (row, number) -> new WorkspaceId(row.getObject(1, UUID.class)),
                    key,
                    actor.accountId());
            if (targets.isEmpty()) {
                throw new AuthException(AuthException.Code.DENIED);
            }
            lockWorkspace(targets.getFirst());
            if (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from auth_api_keys where key_id=? and account_id=? and revoked_at is null)",
                    Boolean.class,
                    key,
                    actor.accountId()))) {
                throw new AuthException(AuthException.Code.DENIED);
            }
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "insert into machine_account_grants(key_id,account_id,permissions) values (?,?,?) "
                                + "on conflict(key_id) do update set permissions=excluded.permissions");
                statement.setObject(1, key);
                statement.setObject(2, actor.accountId());
                statement.setArray(
                        3,
                        connection.createArrayOf(
                                "text", selected.stream().map(Enum::name).toArray(String[]::new)));
                return statement;
            });
            return selected;
        });
    }

    private <T> T human(AuthPrincipal actor, Supplier<T> action) {
        return transactions.execute(status -> {
            lockPolicy();
            return accounts.withAccount(actor, () -> {
                accounts.requireCreator(actor);
                return action.get();
            });
        });
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

    private Set<MachinePermission> grants(UUID key, UUID account) {
        List<Set<MachinePermission>> values = jdbc.query(
                "select permissions from machine_account_grants where key_id=? and account_id=?",
                (row, number) -> Arrays.stream((String[]) row.getArray(1).getArray())
                        .map(MachinePermission::valueOf)
                        .collect(Collectors.toUnmodifiableSet()),
                key,
                account);
        return values.isEmpty() ? Set.of() : values.getFirst();
    }

    public record Identity(UUID accountId, String displayName, Set<MachinePermission> permissions) {
        public Identity {
            permissions = Set.copyOf(permissions);
        }
    }

    public record Connection(UUID keyId, String workspaceName, String clientName, Set<MachinePermission> permissions) {}

    public record ConnectionPage(List<Connection> items, Integer nextOffset) {}
}
