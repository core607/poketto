package io.github.core607.poketto.auth;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Account groups govern the site; changes deliberately preserve memberships and machine grants. */
public final class SitePolicyService {
    private final JdbcTemplate jdbc;
    private final Accounts accounts;
    private final AuthService auth;
    private final TransactionTemplate transactions;

    public SitePolicyService(
            JdbcTemplate jdbc, Accounts accounts, AuthService auth, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.auth = auth;
        this.transactions = new TransactionTemplate(manager);
    }

    public void requireAdministrator(AuthPrincipal actor) {
        if (!accounts.account(actor).siteAdministrator()) {
            throw new AuthException(AuthException.Code.DENIED);
        }
    }

    /** Final account-authority check for a short read, serialized with group and credential changes. */
    public <T> T withAdministrator(AuthPrincipal actor, Supplier<T> operation) {
        return accounts.withAccount(actor, () -> {
            requireAdministrator(actor);
            return operation.get();
        });
    }

    public AuthService.Page<OwnedSpace> ownedSpaces(AuthPrincipal actor, UUID target, int offset, int limit) {
        requirePage(offset, limit);
        return withAdministrator(actor, () -> {
            List<OwnedSpace> items = jdbc.query(
                    "select w.workspace_id,w.public_slug,w.display_name,w.public_delivery,e.eligible "
                            + "from workspaces w join auth_memberships m using(workspace_id) "
                            + "join website_owner_eligibility e using(workspace_id) "
                            + "where m.account_id=? and m.role='OWNER' order by w.workspace_id limit ? offset ?",
                    (row, number) -> new OwnedSpace(
                            row.getObject(1, UUID.class),
                            row.getString(2),
                            row.getString(3),
                            row.getBoolean(4),
                            row.getBoolean(5)),
                    target,
                    limit,
                    offset);
            Long total = jdbc.queryForObject(
                    "select count(*) from auth_memberships where account_id=? and role='OWNER'", Long.class, target);
            return new AuthService.Page<>(items, total, offset, limit);
        });
    }

    public AuthService.Page<Restriction> restrictions(
            AuthPrincipal actor, WorkspaceId workspace, int offset, int limit) {
        requirePage(offset, limit);
        accounts.account(actor);
        return auth.withAuthorization(actor, workspace, Set.of(), () -> {
            if (auth.authorize(actor, workspace).role() != MembershipRole.OWNER) {
                throw new AuthException(AuthException.Code.DENIED);
            }
            String owners = "from auth_memberships m join auth_accounts a using(account_id) "
                    + "where m.workspace_id=? and m.role='OWNER' and a.site_group in ('VIEWER','COMMUNITY')";
            List<Restriction> items = jdbc.query(
                    "select a.display_name,a.site_group,(select c.reason from auth_group_changes c "
                            + "where c.account_id=a.account_id order by c.changed_at desc,c.change_id desc limit 1) "
                            + owners + " order by a.account_id limit ? offset ?",
                    (row, number) ->
                            new Restriction(row.getString(1), SiteGroup.valueOf(row.getString(2)), row.getString(3)),
                    workspace.value(),
                    limit,
                    offset);
            Long total = jdbc.queryForObject("select count(*) " + owners, Long.class, workspace.value());
            return new AuthService.Page<>(items, total, offset, limit);
        });
    }

    public AuthService.Page<AccountSummary> list(AuthPrincipal actor, String query, int offset, int limit) {
        requireAdministrator(actor);
        requirePage(offset, limit);
        if (query == null || query.length() > 254) {
            throw new AuthException(AuthException.Code.INVALID_INPUT);
        }
        String search = query.strip().toLowerCase(Locale.ROOT);
        List<AccountSummary> items = jdbc.query(
                "select account_id,login_name,display_name,site_group,(select count(*) from auth_memberships m "
                        + "where m.account_id=auth_accounts.account_id and m.role='OWNER') as owned_spaces from auth_accounts "
                        + "where position(? in lower(login_name||' '||display_name||' '||coalesce(verified_email,'')))>0 "
                        + "order by login_name,account_id limit ? offset ?",
                (row, number) -> new AccountSummary(
                        row.getObject(1, UUID.class),
                        row.getString(2),
                        row.getString(3),
                        SiteGroup.valueOf(row.getString(4)),
                        row.getLong(5)),
                search,
                limit,
                offset);
        Long total = jdbc.queryForObject(
                "select count(*) from auth_accounts where position(? in lower(login_name||' '||display_name||' '||coalesce(verified_email,'')))>0",
                Long.class,
                search);
        return new AuthService.Page<>(items, total, offset, limit);
    }

    public AccountIdentity change(AuthPrincipal actor, UUID target, SiteGroup group, String reason) {
        if (target == null || group == null || reason == null) {
            throw new AuthException(AuthException.Code.INVALID_INPUT);
        }
        String normalized = reason.strip();
        if (normalized.isEmpty() || normalized.length() > 500) {
            throw new AuthException(AuthException.Code.INVALID_INPUT);
        }
        return transactions.execute(status -> {
            // Serialize group decisions, including the actor's authority and last-admin protection.
            jdbc.queryForObject(
                    "select singleton from auth_initialization where singleton=true for update", Boolean.class);
            requireAdministrator(actor);
            AccountIdentity previous = lockAccount(target);
            if (previous.group() == group) {
                return previous;
            }
            protectAdministrator(previous, group);
            jdbc.update("update auth_accounts set site_group=? where account_id=?", group.name(), target);
            jdbc.update(
                    "insert into auth_group_changes(change_id,actor_id,account_id,previous_group,next_group,reason) "
                            + "values (?,?,?,?,?,?)",
                    UUID.randomUUID(),
                    actor.accountId(),
                    target,
                    previous.group().name(),
                    group.name(),
                    normalized);
            return new AccountIdentity(target, previous.loginName(), previous.displayName(), group);
        });
    }

    private AccountIdentity lockAccount(UUID target) {
        return jdbc
                .query(
                        "select account_id,login_name,display_name,site_group from auth_accounts where account_id=? for update",
                        (row, number) -> new AccountIdentity(
                                row.getObject(1, UUID.class),
                                row.getString(2),
                                row.getString(3),
                                SiteGroup.valueOf(row.getString(4))),
                        target)
                .stream()
                .findFirst()
                .orElseThrow(() -> new AuthException(AuthException.Code.INVALID_INPUT));
    }

    private void protectAdministrator(AccountIdentity previous, SiteGroup group) {
        if (previous.siteAdministrator() && !group.administrator()) {
            Long remaining = jdbc.queryForObject(
                    "select count(*) from auth_accounts where site_group='ADMINISTRATOR'", Long.class);
            if (remaining == null || remaining <= 1) {
                throw new AuthException(AuthException.Code.LAST_ADMINISTRATOR);
            }
        }
    }

    public AuthService.Page<Change> history(AuthPrincipal actor, UUID target, int offset, int limit) {
        requireAdministrator(actor);
        requirePage(offset, limit);
        List<Change> items = jdbc.query(
                "select change_id,actor_id,previous_group,next_group,reason,changed_at from auth_group_changes "
                        + "where account_id=? order by changed_at desc,change_id limit ? offset ?",
                (row, number) -> new Change(
                        row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                        SiteGroup.valueOf(row.getString(3)), SiteGroup.valueOf(row.getString(4)),
                        row.getString(5), row.getTimestamp(6).toInstant()),
                target,
                limit,
                offset);
        Long total =
                jdbc.queryForObject("select count(*) from auth_group_changes where account_id=?", Long.class, target);
        return new AuthService.Page<>(items, total, offset, limit);
    }

    private static void requirePage(int offset, int limit) {
        if (offset < 0 || offset > 100_000 || limit < 1 || limit > 100) {
            throw new AuthException(AuthException.Code.INVALID_INPUT);
        }
    }

    public record Change(
            UUID id, UUID actorId, SiteGroup previousGroup, SiteGroup nextGroup, String reason, Instant changedAt) {}

    public record AccountSummary(
            UUID accountId, String loginName, String displayName, SiteGroup group, long ownedSpaces) {}

    public record OwnedSpace(UUID workspaceId, String slug, String displayName, boolean enabled, boolean eligible) {}

    public record Restriction(String ownerName, SiteGroup group, String reason) {}
}
