package io.github.core607.poketto.auth;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Account groups govern the site; changes deliberately preserve memberships and machine grants. */
public final class SitePolicyService {
    private final JdbcTemplate jdbc;
    private final RegistrationService accounts;
    private final TransactionTemplate transactions;

    public SitePolicyService(JdbcTemplate jdbc, RegistrationService accounts, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.transactions = new TransactionTemplate(manager);
    }

    public void requireAdministrator(AuthPrincipal actor) {
        if (!accounts.account(actor).siteAdministrator()) {
            throw new AuthException(AuthException.Code.DENIED);
        }
    }

    public AuthService.Page<AccountSummary> list(AuthPrincipal actor, String query, int offset, int limit) {
        requireAdministrator(actor);
        requirePage(offset, limit);
        if (query == null || query.length() > 100) {
            throw new AuthException(AuthException.Code.INVALID_INPUT);
        }
        String search = query.strip().toLowerCase(Locale.ROOT);
        List<AccountSummary> items = jdbc.query(
                "select account_id,login_name,site_group,(select count(*) from auth_memberships m "
                        + "where m.account_id=auth_accounts.account_id and m.role='OWNER') as owned_spaces from auth_accounts "
                        + "where position(? in login_name)>0 order by login_name,account_id limit ? offset ?",
                (row, number) -> new AccountSummary(
                        row.getObject(1, UUID.class),
                        row.getString(2),
                        SiteGroup.valueOf(row.getString(3)),
                        row.getLong(4)),
                search,
                limit,
                offset);
        Long total = jdbc.queryForObject(
                "select count(*) from auth_accounts where position(? in login_name)>0", Long.class, search);
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
            return new AccountIdentity(target, previous.loginName(), group);
        });
    }

    private AccountIdentity lockAccount(UUID target) {
        return jdbc
                .query(
                        "select account_id,login_name,site_group from auth_accounts where account_id=? for update",
                        (row, number) -> new AccountIdentity(
                                row.getObject(1, UUID.class), row.getString(2), SiteGroup.valueOf(row.getString(3))),
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

    public record AccountSummary(UUID accountId, String loginName, SiteGroup group, long ownedSpaces) {}
}
