package io.github.core607.poketto.community.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.CommunityAccounts;
import io.github.core607.poketto.auth.CommunityAccounts.Profile;
import io.github.core607.poketto.community.Community.BlockedAccount;
import io.github.core607.poketto.community.Community.Page;
import io.github.core607.poketto.community.Community.Report;
import io.github.core607.poketto.community.Community.ReportInput;
import io.github.core607.poketto.community.CommunityException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class CommunityModeration {
    private final JdbcTemplate jdbc;
    private final CommunityScope scope;
    private final CommunityAccounts accounts;
    private final CommunityComments comments;
    private final CommunityActivity activity;

    CommunityModeration(
            JdbcTemplate jdbc,
            CommunityScope scope,
            CommunityAccounts accounts,
            CommunityComments comments,
            CommunityActivity activity) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.accounts = accounts;
        this.comments = comments;
        this.activity = activity;
    }

    void report(AuthPrincipal actor, UUID id, ReportInput input) {
        CommunityComments.Row existing = comments.get(id, false);
        scope.published(actor, existing.workspace(), false, (identity, snapshot) -> {
            CommunityTargets.article(snapshot, existing.articleId());
            CommunityComments.Row current = comments.get(id, true);
            if (!comments.visible(current, identity.accountId())) {
                throw CommunityTargets.unavailable();
            }
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from community_reports where comment_id=? and reporter_id=?)",
                    Boolean.class,
                    id,
                    identity.accountId()))) {
                return null;
            }
            activity.consume(identity.accountId(), "REPORT", 5, 30);
            jdbc.update(
                    "insert into community_reports(comment_id,reporter_id,reason) values (?,?,?)",
                    id,
                    identity.accountId(),
                    input.reason());
            return null;
        });
    }

    void block(AuthPrincipal actor, UUID target, boolean enabled) {
        if (target == null || target.equals(actor.accountId())) {
            throw new IllegalArgumentException("a block requires another account");
        }
        scope.personal(actor, identity -> {
            if (activity.blocks(identity.accountId(), target) == enabled) {
                return null;
            }
            if (enabled) {
                if (!accounts.profiles(Set.of(target)).containsKey(target)) {
                    throw CommunityTargets.unavailable();
                }
                CommunityActivity.capacity(
                        jdbc.queryForObject(
                                "select count(*) from community_blocks where blocker_id=?",
                                Long.class,
                                identity.accountId()),
                        500);
                activity.consume(identity.accountId(), "BLOCK", 30, 500);
                jdbc.update(
                        "insert into community_blocks(blocker_id,blocked_id) values (?,?) on conflict do nothing",
                        identity.accountId(),
                        target);
            } else {
                jdbc.update(
                        "delete from community_blocks where blocker_id=? and blocked_id=?",
                        identity.accountId(),
                        target);
            }
            return null;
        });
    }

    Page<BlockedAccount> blocks(AuthPrincipal actor, long before) {
        List<BlockRow> rows = jdbc.query(
                "select position,blocked_id from community_blocks where blocker_id=? and position<? order by position desc limit 21",
                (row, number) -> new BlockRow(row.getLong(1), row.getObject(2, UUID.class)),
                actor.accountId(),
                CommunityActivity.before(before));
        return CommunityActivity.page(rows, BlockRow::position, selected -> {
            Set<UUID> ids = new HashSet<>();
            selected.forEach(row -> ids.add(row.account()));
            Map<UUID, Profile> profiles = accounts.profiles(ids);
            return selected.stream()
                    .map(row -> new BlockedAccount(row.position(), profiles.get(row.account())))
                    .toList();
        });
    }

    Page<Report> reports(AuthPrincipal actor, long before) {
        return scope.personal(actor, identity -> {
            if (!identity.siteAdministrator()) {
                throw new CommunityException(CommunityException.Code.DENIED);
            }
            List<ReportRow> rows = jdbc.query(
                    "select r.position,r.comment_id,r.reporter_id,c.author_id,r.reason,c.body,r.created_at,r.status from community_reports r join community_comments c using(comment_id) where r.status='OPEN' and r.position<? order by r.position desc limit 21",
                    (row, number) -> new ReportRow(
                            row.getLong(1),
                            row.getObject(2, UUID.class),
                            row.getObject(3, UUID.class),
                            row.getObject(4, UUID.class),
                            row.getString(5),
                            row.getString(6),
                            row.getTimestamp(7).toInstant(),
                            row.getString(8)),
                    CommunityActivity.before(before));
            return CommunityActivity.page(rows, ReportRow::position, selected -> {
                Set<UUID> ids = new HashSet<>();
                selected.forEach(row -> {
                    ids.add(row.reporter());
                    ids.add(row.author());
                });
                Map<UUID, Profile> profiles = accounts.profiles(ids);
                return selected.stream()
                        .map(row -> new Report(
                                row.position(),
                                row.comment(),
                                profiles.get(row.reporter()),
                                profiles.get(row.author()),
                                row.reason(),
                                row.body(),
                                row.createdAt(),
                                row.status()))
                        .toList();
            });
        });
    }

    void resolve(AuthPrincipal actor, long position, boolean remove) {
        scope.personal(actor, identity -> {
            if (!identity.siteAdministrator()) {
                throw new CommunityException(CommunityException.Code.DENIED);
            }
            List<UUID> ids = jdbc.query(
                    "select comment_id from community_reports where position=?",
                    (row, number) -> row.getObject(1, UUID.class),
                    position);
            if (ids.isEmpty()) {
                throw CommunityTargets.unavailable();
            }
            // Comments precede reports in the lock order, including a report arriving during moderation.
            UUID id = ids.getFirst();
            comments.get(id, true);
            int changed = jdbc.update(
                    "update community_reports set status=?,reviewed_by=?,reviewed_at=current_timestamp where position=? and status='OPEN'",
                    remove ? "REMOVED" : "DISMISSED",
                    identity.accountId(),
                    position);
            if (changed == 1 && remove) {
                comments.hide(id);
            }
            return null;
        });
    }

    private record BlockRow(long position, UUID account) {}

    private record ReportRow(
            long position,
            UUID comment,
            UUID reporter,
            UUID author,
            String reason,
            String body,
            Instant createdAt,
            String status) {}
}
