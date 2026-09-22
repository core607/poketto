package io.github.core607.poketto.auth;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Account-owned community identity; public profiles never include login names or email. */
public final class CommunityAccounts {
    private final JdbcTemplate jdbc;
    private final Accounts accounts;

    public CommunityAccounts(JdbcTemplate jdbc, Accounts accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    /** Lock order is site policy, actor, workspace, then snapshot. Caller owns the transaction. */
    public AccountIdentity lock(AuthPrincipal actor) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("community authority requires a transaction");
        }
        jdbc.queryForObject("select singleton from auth_initialization where singleton=true for share", Boolean.class);
        return accounts.withAccount(actor, () -> accounts.account(actor));
    }

    public Map<UUID, Profile> profiles(Set<UUID> ids) {
        if (ids.size() > 100) {
            throw new IllegalArgumentException("at most 100 community profiles may be resolved at once");
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Profile> profiles = jdbc.query(
                "select account_id,display_name from auth_accounts where account_id in (" + placeholders + ")",
                (row, number) -> new Profile(row.getObject(1, UUID.class), row.getString(2)),
                ids.toArray());
        return profiles.stream().collect(Collectors.toUnmodifiableMap(Profile::accountId, Function.identity()));
    }

    public List<UUID> owners(WorkspaceId workspace) {
        return jdbc.query(
                "select account_id from auth_memberships where workspace_id=? and role='OWNER' and suspended_at is null order by account_id limit 100",
                (row, number) -> row.getObject(1, UUID.class),
                workspace.value());
    }

    public boolean isOwner(UUID account, WorkspaceId workspace) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from auth_memberships where account_id=? and workspace_id=? and role='OWNER' and suspended_at is null)",
                Boolean.class,
                account,
                workspace.value()));
    }

    public record Profile(UUID accountId, String displayName) {}
}
