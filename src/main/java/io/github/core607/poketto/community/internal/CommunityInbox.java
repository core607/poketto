package io.github.core607.poketto.community.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.CommunityAccounts;
import io.github.core607.poketto.auth.CommunityAccounts.Profile;
import io.github.core607.poketto.community.Community.ArticleCard;
import io.github.core607.poketto.community.Community.Notification;
import io.github.core607.poketto.community.Community.Page;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class CommunityInbox {
    private final JdbcTemplate jdbc;
    private final CommunityScope scope;
    private final CommunityTargets targets;
    private final CommunityComments comments;
    private final CommunityAccounts accounts;
    private final CommunityActivity activity;

    CommunityInbox(
            JdbcTemplate jdbc,
            CommunityScope scope,
            CommunityTargets targets,
            CommunityComments comments,
            CommunityAccounts accounts,
            CommunityActivity activity) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.targets = targets;
        this.comments = comments;
        this.accounts = accounts;
        this.activity = activity;
    }

    Page<Notification> list(AuthPrincipal actor, long before) {
        List<Row> rows = jdbc.query(
                "select position,comment_id,read_at is not null from community_notifications where recipient_id=? and position<? order by position desc limit 21",
                (row, number) -> new Row(row.getLong(1), row.getObject(2, UUID.class), row.getBoolean(3)),
                actor.accountId(),
                CommunityActivity.before(before));
        List<Row> selected = rows.stream().limit(20).toList();
        List<Pending> pending = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        for (Row row : selected) {
            CommunityComments.Row comment = comments.get(row.comment(), false);
            if (!comments.visible(comment, actor.accountId())
                    || activity.blocked(actor.accountId(), comment.author())) {
                continue;
            }
            Optional<ArticleCard> card = targets.card(comment.workspace(), comment.articleId());
            if (card.isPresent()) {
                pending.add(new Pending(row, comment, card.get()));
                ids.add(comment.author());
            }
        }
        Map<UUID, Profile> profiles = accounts.profiles(ids);
        List<Notification> items = pending.stream()
                .map(item -> new Notification(
                        item.row().position(),
                        item.comment().id(),
                        profiles.get(item.comment().author()),
                        excerpt(item.comment().body()),
                        item.card(),
                        item.comment().createdAt(),
                        item.row().read()))
                .toList();
        return new Page<>(items, rows.size() > 20 ? selected.getLast().position() : null);
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

    private record Row(long position, UUID comment, boolean read) {}

    private record Pending(Row row, CommunityComments.Row comment, ArticleCard card) {}
}
