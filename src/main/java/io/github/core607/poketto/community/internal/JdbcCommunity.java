package io.github.core607.poketto.community.internal;

import io.github.core607.poketto.auth.AccountIdentity;
import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.SiteGroup;
import io.github.core607.poketto.community.Community;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.List;
import java.util.UUID;

final class JdbcCommunity implements Community {
    private final Accounts accounts;
    private final CommunityTargets targets;
    private final CommunityRelations relations;
    private final CommunityComments comments;
    private final CommunityFeeds feeds;
    private final CommunityInbox inbox;
    private final CommunityModeration moderation;

    JdbcCommunity(
            Accounts accounts,
            CommunityTargets targets,
            CommunityRelations relations,
            CommunityComments comments,
            CommunityFeeds feeds,
            CommunityInbox inbox,
            CommunityModeration moderation) {
        this.accounts = accounts;
        this.targets = targets;
        this.relations = relations;
        this.comments = comments;
        this.feeds = feeds;
        this.inbox = inbox;
        this.moderation = moderation;
    }

    @Override
    public Thread article(AuthPrincipal actor, String space, UUID articleId, UUID parentId, long before) {
        AccountIdentity identity = actor == null ? null : accounts.account(actor);
        UUID account = identity == null ? null : identity.accountId();
        WorkspaceId workspace = targets.workspace(space);
        return targets.read(workspace, snapshot -> {
            CommunityTargets.article(snapshot, articleId);
            boolean moderator = comments.moderator(identity, workspace);
            return new Thread(
                    relations.likes(workspace, articleId),
                    relations.has(account, workspace, articleId, Relation.LIKE),
                    relations.has(account, workspace, articleId, Relation.BOOKMARK),
                    relations.following(account, workspace),
                    account,
                    identity != null && identity.group() != SiteGroup.VIEWER,
                    moderator,
                    comments.page(account, workspace, articleId, parentId, before, moderator));
        });
    }

    @Override
    public SpaceState space(AuthPrincipal actor, String space) {
        AccountIdentity identity = actor == null ? null : accounts.account(actor);
        UUID account = identity == null ? null : identity.accountId();
        WorkspaceId workspace = targets.workspace(space);
        return targets.read(
                workspace,
                snapshot -> new SpaceState(
                        relations.following(account, workspace),
                        account,
                        identity != null && identity.group() != SiteGroup.VIEWER));
    }

    @Override
    public void relate(AuthPrincipal actor, String space, UUID articleId, Relation relation, boolean enabled) {
        accounts.account(actor);
        relations.relate(actor, space, articleId, relation, enabled);
    }

    @Override
    public void follow(AuthPrincipal actor, String space, boolean enabled) {
        accounts.account(actor);
        relations.follow(actor, space, enabled);
    }

    @Override
    public UUID comment(AuthPrincipal actor, String space, UUID articleId, CommentInput input) {
        accounts.account(actor);
        return comments.create(actor, space, articleId, input);
    }

    @Override
    public void deleteComment(AuthPrincipal actor, UUID commentId) {
        accounts.account(actor);
        comments.delete(actor, commentId);
    }

    @Override
    public void moderateComment(AuthPrincipal actor, UUID commentId) {
        accounts.account(actor);
        comments.moderate(actor, commentId);
    }

    @Override
    public void report(AuthPrincipal actor, UUID commentId, ReportInput input) {
        accounts.account(actor);
        moderation.report(actor, commentId, input);
    }

    @Override
    public void block(AuthPrincipal actor, UUID accountId, boolean enabled) {
        accounts.account(actor);
        moderation.block(actor, accountId, enabled);
    }

    @Override
    public Page<SavedArticle> bookmarks(AuthPrincipal actor, long before) {
        accounts.account(actor);
        return feeds.saved(actor, before, Relation.BOOKMARK);
    }

    @Override
    public void removeBookmark(AuthPrincipal actor, long position) {
        accounts.account(actor);
        feeds.removeSaved(actor, position, Relation.BOOKMARK);
    }

    @Override
    public Page<SavedArticle> likes(AuthPrincipal actor, long before) {
        accounts.account(actor);
        return feeds.saved(actor, before, Relation.LIKE);
    }

    @Override
    public void removeLike(AuthPrincipal actor, long position) {
        accounts.account(actor);
        feeds.removeSaved(actor, position, Relation.LIKE);
    }

    @Override
    public void unfollow(AuthPrincipal actor, long position) {
        accounts.account(actor);
        feeds.unfollow(actor, position);
    }

    @Override
    public Feed feed(AuthPrincipal actor, String cursor) {
        accounts.account(actor);
        return feeds.feed(actor, cursor);
    }

    @Override
    public List<FollowedSpace> following(AuthPrincipal actor) {
        accounts.account(actor);
        return feeds.following(actor);
    }

    @Override
    public Page<Notification> notifications(AuthPrincipal actor, long before) {
        accounts.account(actor);
        return inbox.list(actor, before);
    }

    @Override
    public void markRead(AuthPrincipal actor, List<Long> positions) {
        accounts.account(actor);
        inbox.markRead(actor, positions);
    }

    @Override
    public Page<BlockedAccount> blocks(AuthPrincipal actor, long before) {
        accounts.account(actor);
        return moderation.blocks(actor, before);
    }

    @Override
    public Page<Report> reports(AuthPrincipal actor, long before) {
        accounts.account(actor);
        return moderation.reports(actor, before);
    }

    @Override
    public void resolveReport(AuthPrincipal actor, long position, boolean remove) {
        accounts.account(actor);
        moderation.resolve(actor, position, remove);
    }
}
