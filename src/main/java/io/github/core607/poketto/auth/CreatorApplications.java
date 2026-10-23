package io.github.core607.poketto.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Viewers and community members ask for creator eligibility here; only a site administrator's decision
 * changes the group, through the ordinary audited group change.
 */
public final class CreatorApplications {
    /** A declined applicant waits this long before asking again, so the queue cannot be flooded. */
    public static final Duration COOLDOWN = Duration.ofDays(7);

    private final JdbcTemplate jdbc;
    private final Accounts accounts;
    private final SitePolicyService policies;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CreatorApplications(
            JdbcTemplate jdbc,
            Accounts accounts,
            SitePolicyService policies,
            PlatformTransactionManager manager,
            Clock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.policies = policies;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
    }

    /** The applicant's latest application and whether a new one may be submitted now. */
    public Standing standing(AuthPrincipal actor) {
        AccountIdentity account = accounts.account(actor);
        Application latest = latest(account.accountId());
        return new Standing(account.group(), latest, availableAt(account, latest));
    }

    public Application apply(AuthPrincipal actor, String statement) {
        String text = text(statement, 1000, false);
        return accounts.withAccount(actor, () -> {
            AccountIdentity account = accounts.account(actor);
            Application latest = latest(account.accountId());
            if (account.group().mayCreateSpace()) {
                throw new AuthException(AuthException.Code.ALREADY_CREATOR);
            }
            if (latest != null && latest.status() == Status.OPEN) {
                throw new AuthException(AuthException.Code.APPLICATION_OPEN);
            }
            Instant available = availableAt(account, latest);
            if (available != null && clock.instant().isBefore(available)) {
                throw new AuthException(AuthException.Code.APPLICATION_COOLDOWN);
            }
            UUID id = UUID.randomUUID();
            jdbc.update(
                    "insert into creator_applications(application_id,account_id,statement,created_at) values (?,?,?,?)",
                    id,
                    account.accountId(),
                    text,
                    java.sql.Timestamp.from(clock.instant()));
            return latest(account.accountId());
        });
    }

    public AuthService.Page<Queued> list(AuthPrincipal actor, Status status, int offset, int limit) {
        if (status == null || offset < 0 || offset > 100_000 || limit < 1 || limit > 100) {
            throw new AuthException(AuthException.Code.INVALID_INPUT);
        }
        return policies.withAdministrator(actor, () -> {
            List<Queued> items = jdbc.query(
                    "select c.application_id,c.statement,c.status,c.created_at,c.decided_at,c.decision_note,"
                            + "a.account_id,a.login_name,a.display_name,a.site_group "
                            + "from creator_applications c join auth_accounts a using(account_id) where c.status=? "
                            + "order by c.created_at " + (status == Status.OPEN ? "asc" : "desc")
                            + ",c.application_id limit ? offset ?",
                    (row, number) -> new Queued(
                            new Application(
                                    row.getObject(1, UUID.class),
                                    row.getString(2),
                                    Status.valueOf(row.getString(3)),
                                    row.getTimestamp(4).toInstant(),
                                    row.getTimestamp(5) == null
                                            ? null
                                            : row.getTimestamp(5).toInstant(),
                                    row.getString(6)),
                            row.getObject(7, UUID.class),
                            row.getString(8),
                            row.getString(9),
                            SiteGroup.valueOf(row.getString(10))),
                    status.name(),
                    limit,
                    offset);
            Long total = jdbc.queryForObject(
                    "select count(*) from creator_applications where status=?", Long.class, status.name());
            return new AuthService.Page<>(items, total, offset, limit);
        });
    }

    public long open(AuthPrincipal actor) {
        return policies.withAdministrator(actor, () -> {
            Long count =
                    jdbc.queryForObject("select count(*) from creator_applications where status='OPEN'", Long.class);
            return count == null ? 0 : count;
        });
    }

    /**
     * Approving grants CREATOR to a viewer or community member and records the group change; an applicant
     * who already reached creator or administrator through another change keeps their group. Declining
     * requires a note the applicant can read.
     */
    public Application decide(AuthPrincipal actor, UUID id, boolean approve, String note) {
        String text = text(note, 500, approve);
        return transactions.execute(status -> {
            // The same lock order as group changes: the site singleton, then the application.
            jdbc.queryForObject(
                    "select singleton from auth_initialization where singleton=true for update", Boolean.class);
            policies.requireAdministrator(actor);
            List<UUID> applicants = jdbc.query(
                    "select account_id from creator_applications where application_id=? and status='OPEN' for update",
                    (row, number) -> row.getObject(1, UUID.class),
                    id);
            if (applicants.isEmpty()) {
                throw new AuthException(AuthException.Code.INVALID_INPUT);
            }
            UUID applicant = applicants.getFirst();
            if (approve) {
                SiteGroup group = jdbc.queryForObject(
                        "select site_group from auth_accounts where account_id=?",
                        (row, number) -> SiteGroup.valueOf(row.getString(1)),
                        applicant);
                if (group != null && !group.mayCreateSpace()) {
                    policies.change(
                            actor,
                            applicant,
                            SiteGroup.CREATOR,
                            text.isEmpty() ? "Creator application approved" : "Creator application approved: " + text);
                }
            }
            jdbc.update(
                    "update creator_applications set status=?,decided_by=?,decided_at=?,decision_note=? "
                            + "where application_id=?",
                    approve ? Status.APPROVED.name() : Status.DECLINED.name(),
                    actor.accountId(),
                    java.sql.Timestamp.from(clock.instant()),
                    text.isEmpty() ? null : text,
                    id);
            return latest(applicant);
        });
    }

    private Instant availableAt(AccountIdentity account, Application latest) {
        if (account.group().mayCreateSpace() || latest == null) {
            return null;
        }
        if (latest.status() == Status.DECLINED && latest.decidedAt() != null) {
            return latest.decidedAt().plus(COOLDOWN);
        }
        return null;
    }

    private Application latest(UUID account) {
        return jdbc
                .query(
                        "select application_id,statement,status,created_at,decided_at,decision_note "
                                + "from creator_applications where account_id=? "
                                + "order by created_at desc,application_id limit 1",
                        (row, number) -> new Application(
                                row.getObject(1, UUID.class),
                                row.getString(2),
                                Status.valueOf(row.getString(3)),
                                row.getTimestamp(4).toInstant(),
                                row.getTimestamp(5) == null
                                        ? null
                                        : row.getTimestamp(5).toInstant(),
                                row.getString(6)),
                        account)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static String text(String value, int limit, boolean optional) {
        String normalized = value == null ? "" : value.strip();
        if ((!optional && normalized.isEmpty()) || normalized.length() > limit) {
            throw new AuthException(AuthException.Code.INVALID_INPUT);
        }
        return normalized;
    }

    public enum Status {
        OPEN,
        APPROVED,
        DECLINED;

        public static Status parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException | NullPointerException invalid) {
                throw new AuthException(AuthException.Code.INVALID_INPUT);
            }
        }
    }

    public record Application(
            UUID id, String statement, Status status, Instant createdAt, Instant decidedAt, String decisionNote) {}

    /** The applicant's view. availableAt is when a declined applicant may ask again, or null. */
    public record Standing(SiteGroup group, Application latest, Instant availableAt) {}

    public record Queued(
            Application application, UUID accountId, String loginName, String displayName, SiteGroup group) {}
}
