package io.github.core607.poketto.auth;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Account groups govern the site; changes deliberately preserve memberships and machine grants. */
public final class SitePolicyService {
    private static final String ACCOUNT_SELECT =
            "select account_id,login_name,display_name,site_group,(select count(*) from auth_memberships m "
                    + "where m.account_id=auth_accounts.account_id and m.role='OWNER') as owned_spaces,created_at,"
                    + "suspended_at is not null from auth_accounts ";
    private final JdbcTemplate jdbc;
    private final Accounts accounts;
    private final AuthService auth;
    private final TransactionTemplate transactions;
    private final CredentialRevocations revocations;

    public SitePolicyService(
            JdbcTemplate jdbc, Accounts accounts, AuthService auth, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.auth = auth;
        this.transactions = new TransactionTemplate(manager);
        this.revocations = new CredentialRevocations(jdbc, auth);
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
                    + "where m.workspace_id=? and m.role='OWNER' "
                    + "and (a.site_group in ('VIEWER','COMMUNITY') or a.suspended_at is not null)";
            List<Restriction> items = jdbc.query(
                    "select a.display_name,a.site_group,(select c.reason from auth_group_changes c "
                            + "where c.account_id=a.account_id order by c.changed_at desc,c.change_id desc limit 1),"
                            + "a.suspended_at is not null "
                            + owners + " order by a.account_id limit ? offset ?",
                    (row, number) -> new Restriction(
                            row.getString(1),
                            SiteGroup.valueOf(row.getString(2)),
                            // A suspension reason is between the account and the site; co-owners see only the fact.
                            row.getBoolean(4) ? null : row.getString(3),
                            row.getBoolean(4)),
                    workspace.value(),
                    limit,
                    offset);
            Long total = jdbc.queryForObject("select count(*) " + owners, Long.class, workspace.value());
            return new AuthService.Page<>(items, total, offset, limit);
        });
    }

    public AuthService.Page<AccountSummary> list(AuthPrincipal actor, String query, int offset, int limit) {
        return list(actor, query, null, false, AccountOrder.NAME, offset, limit);
    }

    public Optional<AccountSummary> account(AuthPrincipal actor, UUID id) {
        return withAdministrator(
                actor,
                () -> jdbc.query(ACCOUNT_SELECT + "where account_id=?", this::accountRow, id).stream()
                        .findFirst());
    }

    public Optional<OwnedSpace> space(AuthPrincipal actor, UUID id) {
        return withAdministrator(
                actor,
                () -> jdbc
                        .query(
                                "select w.workspace_id,w.public_slug,w.display_name,w.public_delivery,e.eligible "
                                        + "from workspaces w join website_owner_eligibility e using(workspace_id) where w.workspace_id=?",
                                (row, number) -> new OwnedSpace(
                                        row.getObject(1, UUID.class),
                                        row.getString(2),
                                        row.getString(3),
                                        row.getBoolean(4),
                                        row.getBoolean(5)),
                                id)
                        .stream()
                        .findFirst());
    }

    private AccountSummary accountRow(ResultSet row, int number) throws SQLException {
        return new AccountSummary(
                row.getObject(1, UUID.class),
                row.getString(2),
                row.getString(3),
                SiteGroup.valueOf(row.getString(4)),
                row.getLong(5),
                row.getTimestamp(6).toInstant(),
                row.getBoolean(7));
    }

    /** Searches accounts by login, display name or email, optionally within one group or only suspended ones. */
    public AuthService.Page<AccountSummary> list(
            AuthPrincipal actor,
            String query,
            SiteGroup group,
            boolean suspendedOnly,
            AccountOrder order,
            int offset,
            int limit) {
        requireAdministrator(actor);
        requirePage(offset, limit);
        if (query == null || query.length() > 254 || order == null) {
            throw new AuthException(AuthException.Code.INVALID_INPUT);
        }
        String search = query.strip().toLowerCase(Locale.ROOT);
        String groupName = group == null ? null : group.name();
        String where = "where position(? in lower(login_name||' '||display_name||' '||coalesce(verified_email,'')))>0 "
                + "and (?::text is null or site_group=?::text) "
                + (suspendedOnly ? "and suspended_at is not null " : "");
        List<AccountSummary> items = jdbc.query(
                ACCOUNT_SELECT
                        + where
                        + (order == AccountOrder.NEWEST
                                ? "order by created_at desc,account_id "
                                : "order by login_name,account_id ")
                        + "limit ? offset ?",
                this::accountRow,
                search,
                groupName,
                groupName,
                limit,
                offset);
        Long total = jdbc.queryForObject(
                "select count(*) from auth_accounts " + where, Long.class, search, groupName, groupName);
        return new AuthService.Page<>(items, total, offset, limit);
    }

    /** Every space on the site with its owners and website state, for moderation. */
    public AuthService.Page<SiteSpace> spaces(
            AuthPrincipal actor, String query, SpaceState state, int offset, int limit) {
        requirePage(offset, limit);
        if (query == null || query.length() > 254 || state == null) {
            throw new AuthException(AuthException.Code.INVALID_INPUT);
        }
        String search = query.strip().toLowerCase(Locale.ROOT);
        String where = "from workspaces w join website_owner_eligibility e using(workspace_id) "
                + "where position(? in lower(w.display_name||' '||coalesce(w.public_slug,'')))>0 and "
                + switch (state) {
                    case ALL -> "true ";
                    case PUBLIC -> "w.public_delivery and e.eligible ";
                    case RESTRICTED -> "not e.eligible ";
                    case CLOSED -> "not w.public_delivery and e.eligible ";
                };
        return withAdministrator(actor, () -> {
            List<SiteSpace> items = jdbc.query(
                    "select w.workspace_id,w.public_slug,w.display_name,w.public_delivery,e.eligible,w.created_at,"
                            + "array(select a.display_name from auth_memberships m join auth_accounts a using(account_id) "
                            + "where m.workspace_id=w.workspace_id and m.role='OWNER' order by a.display_name,a.account_id limit 3),"
                            + "(select count(*) from auth_memberships m where m.workspace_id=w.workspace_id and m.role='OWNER') "
                            + where
                            + "order by w.created_at desc,w.workspace_id limit ? offset ?",
                    (row, number) -> new SiteSpace(
                            row.getObject(1, UUID.class),
                            row.getString(2),
                            row.getString(3),
                            row.getBoolean(4),
                            row.getBoolean(5),
                            row.getTimestamp(6).toInstant(),
                            List.of((String[]) row.getArray(7).getArray()),
                            row.getLong(8)),
                    search,
                    limit,
                    offset);
            Long total = jdbc.queryForObject("select count(*) " + where, Long.class, search);
            return new AuthService.Page<>(items, total, offset, limit);
        });
    }

    /** Site-wide counts for the administration overview. */
    public Overview overview(AuthPrincipal actor, Instant since) {
        if (since == null) {
            throw new AuthException(AuthException.Code.INVALID_INPUT);
        }
        return withAdministrator(actor, () -> {
            Map<SiteGroup, Long> groups = new EnumMap<>(SiteGroup.class);
            for (SiteGroup group : SiteGroup.values()) {
                groups.put(group, 0L);
            }
            jdbc.query("select site_group,count(*) from auth_accounts group by site_group", row -> {
                groups.put(SiteGroup.valueOf(row.getString(1)), row.getLong(2));
            });
            Long suspended = jdbc.queryForObject(
                    "select count(*) from auth_accounts where suspended_at is not null", Long.class);
            Long recent = jdbc.queryForObject(
                    "select count(*) from auth_accounts where created_at>=?",
                    Long.class,
                    java.sql.Timestamp.from(since));
            return jdbc.queryForObject(
                    "select count(*),count(*) filter (where w.public_delivery and e.eligible),"
                            + "count(*) filter (where not e.eligible) "
                            + "from workspaces w join website_owner_eligibility e using(workspace_id)",
                    (row, number) -> new Overview(
                            groups,
                            recent == null ? 0 : recent,
                            suspended == null ? 0 : suspended,
                            row.getLong(1),
                            row.getLong(2),
                            row.getLong(3)));
        });
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

    /**
     * Suspends or restores an account. Suspension ends its sessions and machine credentials and withdraws
     * websites it owns; memberships, group and content stay, so restoring needs only a new login and keys.
     */
    public boolean suspend(AuthPrincipal actor, UUID target, boolean suspended, String reason) {
        if (target == null || reason == null) {
            throw new AuthException(AuthException.Code.INVALID_INPUT);
        }
        String normalized = reason.strip();
        if (normalized.isEmpty() || normalized.length() > 500) {
            throw new AuthException(AuthException.Code.INVALID_INPUT);
        }
        return transactions.execute(status -> {
            jdbc.queryForObject(
                    "select singleton from auth_initialization where singleton=true for update", Boolean.class);
            requireAdministrator(actor);
            AccountIdentity account = lockAccount(target);
            // Administrators are demoted first, which keeps the last-administrator rule in one place.
            if (account.accountId().equals(actor.accountId()) || account.siteAdministrator()) {
                throw new AuthException(AuthException.Code.PROTECTED_ACCOUNT);
            }
            Boolean current = jdbc.queryForObject(
                    "select suspended_at is not null from auth_accounts where account_id=?", Boolean.class, target);
            if (Boolean.valueOf(suspended).equals(current)) {
                return suspended;
            }
            if (suspended) {
                jdbc.update(
                        "update auth_accounts set suspended_at=?,suspension_reason=?,"
                                + "credential_version=credential_version+1 where account_id=?",
                        auth.timestamp(),
                        normalized,
                        target);
                revocations.revoke(target);
            } else {
                jdbc.update(
                        "update auth_accounts set suspended_at=null,suspension_reason=null where account_id=?", target);
            }
            jdbc.update(
                    "insert into auth_suspension_changes(change_id,actor_id,account_id,suspended,reason) values (?,?,?,?,?)",
                    UUID.randomUUID(),
                    actor.accountId(),
                    target,
                    suspended,
                    normalized);
            return suspended;
        });
    }

    public AuthService.Page<SuspensionChange> suspensionHistory(
            AuthPrincipal actor, UUID target, int offset, int limit) {
        requireAdministrator(actor);
        requirePage(offset, limit);
        List<SuspensionChange> items = jdbc.query(
                "select change_id,actor_id,suspended,reason,changed_at from auth_suspension_changes "
                        + "where account_id=? order by changed_at desc,change_id limit ? offset ?",
                (row, number) -> new SuspensionChange(
                        row.getObject(1, UUID.class),
                        row.getObject(2, UUID.class),
                        row.getBoolean(3),
                        row.getString(4),
                        row.getTimestamp(5).toInstant()),
                target,
                limit,
                offset);
        Long total = jdbc.queryForObject(
                "select count(*) from auth_suspension_changes where account_id=?", Long.class, target);
        return new AuthService.Page<>(items, total, offset, limit);
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
            UUID accountId,
            String loginName,
            String displayName,
            SiteGroup group,
            long ownedSpaces,
            Instant createdAt,
            boolean suspended) {}

    public enum AccountOrder {
        NAME,
        NEWEST
    }

    /**
     * Website states as moderation filters them. RESTRICTED means the owner-eligibility view refuses the
     * space: an owner lacks creator eligibility or is suspended, or the space has no owner.
     */
    public enum SpaceState {
        ALL,
        PUBLIC,
        RESTRICTED,
        CLOSED
    }

    public record SiteSpace(
            UUID workspaceId,
            String slug,
            String displayName,
            boolean enabled,
            boolean eligible,
            Instant createdAt,
            List<String> owners,
            long ownerCount) {}

    public record Overview(
            Map<SiteGroup, Long> accounts,
            long newAccounts,
            long suspendedAccounts,
            long spaces,
            long publicSpaces,
            long restrictedSpaces) {}

    public record SuspensionChange(UUID id, UUID actorId, boolean suspended, String reason, Instant changedAt) {}

    public record OwnedSpace(UUID workspaceId, String slug, String displayName, boolean enabled, boolean eligible) {}

    public record Restriction(String ownerName, SiteGroup group, String reason, boolean suspended) {}
}
