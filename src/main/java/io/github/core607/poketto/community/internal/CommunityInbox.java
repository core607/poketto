package io.github.core607.poketto.community.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.CommunityAccounts;
import io.github.core607.poketto.auth.CommunityAccounts.Profile;
import io.github.core607.poketto.community.Community.ArticleCard;
import io.github.core607.poketto.community.Community.Notification;
import io.github.core607.poketto.community.Community.Page;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

final class CommunityInbox {
    private final JdbcTemplate jdbc;
    private final CommunityScope scope;
    private final CommunityTargets targets;
    private final CommunityComments comments;
    private final CommunityCorrections corrections;
    private final CommunityAccounts accounts;
    private final CommunityActivity activity;

    CommunityInbox(
            JdbcTemplate jdbc,
            CommunityScope scope,
            CommunityTargets targets,
            CommunityComments comments,
            CommunityCorrections corrections,
            CommunityAccounts accounts,
            CommunityActivity activity) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.targets = targets;
        this.comments = comments;
        this.corrections = corrections;
        this.accounts = accounts;
        this.activity = activity;
    }

    Page<Notification> list(AuthPrincipal actor, long before) {
        List<Row> rows = jdbc.query(
                "select position,comment_id,correction_id,event,read_at is not null from community_notifications where recipient_id=? and position<? order by position desc limit 21",
                (row, number) -> new Row(
                        row.getLong(1),
                        row.getObject(2, UUID.class),
                        row.getObject(3, UUID.class),
                        row.getString(4),
                        row.getBoolean(5)),
                actor.accountId(),
                CommunityActivity.before(before));
        List<Row> selected = rows.stream().limit(20).toList();
        List<Pending> pending = new ArrayList<>();
        for (Row row : selected) {
            (row.comment() != null ? comment(actor, row) : correction(actor, row)).ifPresent(pending::add);
        }
        Map<UUID, Profile> profiles = accounts.profiles(
                pending.stream().map(Pending::actor).filter(Objects::nonNull).collect(Collectors.toSet()));
        List<Notification> items = pending.stream()
                .map(item -> new Notification(
                        item.row().position(),
                        item.row().comment(),
                        item.actor() == null ? null : profiles.get(item.actor()),
                        item.excerpt(),
                        item.card(),
                        item.at(),
                        item.row().read(),
                        item.row().correction(),
                        item.row().event()))
                .toList();
        return new Page<>(items, rows.size() > 20 ? selected.getLast().position() : null);
    }

    private Optional<Pending> comment(AuthPrincipal actor, Row row) {
        CommunityComments.Row comment = comments.get(row.comment(), false);
        if (!comments.visible(comment, actor.accountId()) || activity.blocked(actor.accountId(), comment.author())) {
            return Optional.empty();
        }
        return targets.card(comment.workspace(), comment.articleId())
                .map(card -> new Pending(row, comment.author(), excerpt(comment.body()), card, comment.createdAt()));
    }

    /** A proposal names its proposer; a resolution names the member who resolved it. */
    private Optional<Pending> correction(AuthPrincipal actor, Row row) {
        Optional<CommunityCorrections.Row> found = corrections.find(row.correction());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        CommunityCorrections.Row correction = found.get();
        boolean proposed = "PROPOSED".equals(row.event());
        UUID by = proposed ? correction.author() : correction.resolver();
        if (activity.blocked(actor.accountId(), by)) {
            return Optional.empty();
        }
        return targets.card(correction.workspace(), correction.route())
                .map(card -> new Pending(
                        row,
                        by,
                        proposed ? excerpt(correction.reason()) : "",
                        card,
                        proposed ? correction.createdAt() : correction.resolvedAt()));
    }

    void markRead(AuthPrincipal actor, List<Long> positions) {
        if (positions == null || positions.isEmpty() || positions.size() > 50) {
            throw new IllegalArgumentException("mark-read requires one to fifty notification positions");
        }
        if (positions.stream().anyMatch(position -> position == null || position <= 0)) {
            throw new IllegalArgumentException("notification positions must be positive");
        }
        scope.personal(actor, identity -> {
            List<Object> arguments = new ArrayList<>();
            arguments.add(identity.accountId());
            arguments.addAll(positions);
            jdbc.update(
                    "update community_notifications set read_at=coalesce(read_at,current_timestamp) where recipient_id=? and position in ("
                            + String.join(",", Collections.nCopies(positions.size(), "?")) + ")",
                    arguments.toArray());
            return null;
        });
    }

    private static String excerpt(String body) {
        return body.substring(0, body.offsetByCodePoints(0, Math.min(160, body.codePointCount(0, body.length()))));
    }

    private record Row(long position, UUID comment, UUID correction, String event, boolean read) {}

    private record Pending(Row row, UUID actor, String excerpt, ArticleCard card, Instant at) {}
}
