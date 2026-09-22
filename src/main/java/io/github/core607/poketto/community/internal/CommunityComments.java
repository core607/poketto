package io.github.core607.poketto.community.internal;

import io.github.core607.poketto.auth.AccountIdentity;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.CommunityAccounts;
import io.github.core607.poketto.auth.CommunityAccounts.Profile;
import io.github.core607.poketto.community.Community.Comment;
import io.github.core607.poketto.community.Community.CommentInput;
import io.github.core607.poketto.community.Community.Page;
import io.github.core607.poketto.community.CommunityException;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

final class CommunityComments {
    private static final String COLUMNS =
            "c.comment_id,c.position,c.workspace_id,c.article_id,c.author_id,c.parent_id,c.body,c.created_at,c.deleted_at,c.hidden_at,c.request_digest";
    private final JdbcTemplate jdbc;
    private final CommunityAccounts accounts;
    private final CommunityScope scope;
    private final CommunityTargets targets;
    private final CommunityActivity activity;

    CommunityComments(
            JdbcTemplate jdbc,
            CommunityAccounts accounts,
            CommunityScope scope,
            CommunityTargets targets,
            CommunityActivity activity) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.scope = scope;
        this.targets = targets;
        this.activity = activity;
    }

    UUID create(AuthPrincipal actor, String space, UUID articleId, CommentInput input) {
        WorkspaceId workspace = targets.workspace(space);
        String digest = DocumentRevision.sha256(
                        (workspace + "\n" + articleId + "\n" + input.parentId() + "\n" + input.body())
                                .getBytes(StandardCharsets.UTF_8))
                .value();
        return scope.published(actor, workspace, true, (identity, snapshot) -> {
            CommunityTargets.article(snapshot, articleId);
            Optional<Row> replay = jdbc
                    .query(
                            "select " + COLUMNS + " from community_comments c where author_id=? and request_id=?",
                            CommunityComments::row,
                            identity.accountId(),
                            input.requestId())
                    .stream()
                    .findFirst();
            if (replay.isPresent()) {
                if (!digest.equals(replay.get().digest())) {
                    throw new CommunityException(CommunityException.Code.REQUEST_CONFLICT);
                }
                return replay.get().id();
            }
            Row parent = input.parentId() == null
                    ? null
                    : requireRoot(input.parentId(), workspace, articleId, identity.accountId(), true);
            activity.consume(identity.accountId(), "COMMENT", 10, 300);
            UUID id = UUID.randomUUID();
            jdbc.update(
                    "insert into community_comments(comment_id,request_id,workspace_id,article_id,author_id,parent_id,body,request_digest) values (?,?,?,?,?,?,?,?)",
                    id,
                    input.requestId(),
                    workspace.value(),
                    articleId,
                    identity.accountId(),
                    input.parentId(),
                    input.body(),
                    digest);
            List<UUID> recipients = parent == null ? accounts.owners(workspace) : List.of(parent.author());
            notifyRecipients(id, identity.accountId(), recipients);
            return id;
        });
    }

    private void notifyRecipients(UUID id, UUID actor, List<UUID> recipients) {
        for (UUID recipient : recipients) {
            if (recipient.equals(actor) || activity.blocked(actor, recipient)) {
                continue;
            }
            // Separate from account locks: recovery may hold the recipient while awaiting this workspace.
            jdbc.queryForObject(
                    "select pg_advisory_xact_lock(hashtextextended('community-inbox:' || ?::text,0))",
                    Object.class,
                    recipient);
            jdbc.update(
                    "insert into community_notifications(recipient_id,comment_id) values (?,?) on conflict do nothing",
                    recipient,
                    id);
            jdbc.update(
                    "delete from community_notifications where recipient_id=? and position < coalesce((select position from community_notifications where recipient_id=? order by position desc offset 999 limit 1),0)",
                    recipient,
                    recipient);
        }
    }

    Page<Comment> page(
            UUID viewer, WorkspaceId workspace, UUID articleId, UUID parentId, long before, boolean moderator) {
        long cursor = CommunityActivity.before(before);
        if (parentId != null) {
            Row root = requireRoot(parentId, workspace, articleId, viewer, false);
            if (root.hidden()) {
                throw CommunityTargets.unavailable();
            }
        }
        List<Row> rows = jdbc.query(
                "select " + COLUMNS
                        + " from community_comments c where c.workspace_id=? and c.article_id=? and c.parent_id is not distinct from ?::uuid and c.position<? and c.hidden_at is null "
                        + "and not exists(select 1 from community_blocks b where b.blocker_id=? and b.blocked_id=c.author_id) order by c.position desc limit 21",
                CommunityComments::row,
                workspace.value(),
                articleId,
                parentId,
                cursor,
                viewer);
        List<Row> selected = rows.stream().limit(20).toList();
        Set<UUID> authors =
                selected.stream().filter(row -> !row.deleted()).map(Row::author).collect(Collectors.toSet());
        Map<UUID, Profile> profiles = accounts.profiles(authors);
        List<Comment> comments = selected.stream()
                .map(row -> new Comment(
                        row.id(),
                        row.position(),
                        row.parent(),
                        row.deleted() ? null : profiles.get(row.author()),
                        row.deleted() ? "" : row.body(),
                        row.createdAt(),
                        row.deleted(),
                        row.parent() == null ? replyCount(row.id(), viewer) : 0,
                        moderator || row.author().equals(viewer)))
                .toList();
        return new Page<>(comments, rows.size() > 20 ? selected.getLast().position() : null);
    }

    private long replyCount(UUID root, UUID viewer) {
        return jdbc.queryForObject(
                "select count(*) from community_comments c where c.parent_id=? and c.hidden_at is null and not exists(select 1 from community_blocks b where b.blocker_id=? and b.blocked_id=c.author_id)",
                Long.class,
                root,
                viewer);
    }

    Row requireRoot(UUID parentId, WorkspaceId workspace, UUID articleId, UUID actor, boolean posting) {
        Row root = get(parentId, posting);
        boolean invalid = root.parent() != null
                || !root.workspace().equals(workspace)
                || !root.articleId().equals(articleId)
                || root.hidden()
                || (posting && root.deleted())
                || (posting && activity.blocked(actor, root.author()));
        if (invalid) {
            throw new CommunityException(CommunityException.Code.REPLY_UNAVAILABLE);
        }
        if (actor != null
                && Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from community_blocks where blocker_id=? and blocked_id=?)",
                        Boolean.class,
                        actor,
                        root.author()))) {
            throw CommunityTargets.unavailable();
        }
        return root;
    }

    Row get(UUID id, boolean lock) {
        return jdbc
                .query(
                        "select " + COLUMNS + " from community_comments c where c.comment_id=?"
                                + (lock ? " for update" : ""),
                        CommunityComments::row,
                        id)
                .stream()
                .findFirst()
                .orElseThrow(CommunityTargets::unavailable);
    }

    boolean visible(Row row, UUID viewer) {
        if (row.hidden() || row.deleted()) {
            return false;
        }
        if (viewer != null
                && Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from community_blocks where blocker_id=? and blocked_id=?)",
                        Boolean.class,
                        viewer,
                        row.author()))) {
            return false;
        }
        if (row.parent() != null) {
            Row parent = get(row.parent(), false);
            return !parent.hidden()
                    && (viewer == null
                            || !Boolean.TRUE.equals(jdbc.queryForObject(
                                    "select exists(select 1 from community_blocks where blocker_id=? and blocked_id=?)",
                                    Boolean.class,
                                    viewer,
                                    parent.author())));
        }
        return true;
    }

    void delete(AuthPrincipal actor, UUID id) {
        Row existing = get(id, false);
        if (existing.author().equals(actor.accountId())) {
            scope.personal(actor, identity -> {
                jdbc.update(
                        "update community_comments set body='',deleted_at=coalesce(deleted_at,current_timestamp) where comment_id=? and author_id=?",
                        id,
                        identity.accountId());
                return null;
            });
            return;
        }
        moderate(actor, id);
    }

    void moderate(AuthPrincipal actor, UUID id) {
        Row existing = get(id, false);
        scope.published(actor, existing.workspace(), false, (identity, snapshot) -> {
            CommunityTargets.article(snapshot, existing.articleId());
            requireModerator(identity, existing.workspace());
            hide(id);
            return null;
        });
    }

    boolean moderator(AccountIdentity identity, WorkspaceId workspace) {
        return identity != null && (identity.siteAdministrator() || accounts.isOwner(identity.accountId(), workspace));
    }

    void requireModerator(AccountIdentity identity, WorkspaceId workspace) {
        if (!moderator(identity, workspace)) {
            throw new CommunityException(CommunityException.Code.DENIED);
        }
    }

    void hide(UUID id) {
        jdbc.update(
                "update community_comments set hidden_at=coalesce(hidden_at,current_timestamp) where comment_id=?", id);
        jdbc.update(
                "update community_reports set status='REMOVED',reviewed_at=current_timestamp where comment_id=? and status='OPEN'",
                id);
    }

    private static Row row(ResultSet row, int number) throws SQLException {
        return new Row(
                row.getObject(1, UUID.class),
                row.getLong(2),
                new WorkspaceId(row.getObject(3, UUID.class)),
                row.getObject(4, UUID.class),
                row.getObject(5, UUID.class),
                row.getObject(6, UUID.class),
                row.getString(7),
                row.getTimestamp(8).toInstant(),
                row.getTimestamp(9) != null,
                row.getTimestamp(10) != null,
                row.getString(11));
    }

    record Row(
            UUID id,
            long position,
            WorkspaceId workspace,
            UUID articleId,
            UUID author,
            UUID parent,
            String body,
            Instant createdAt,
            boolean deleted,
            boolean hidden,
            String digest) {}
}
