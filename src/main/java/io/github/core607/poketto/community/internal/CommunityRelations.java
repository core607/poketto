package io.github.core607.poketto.community.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.community.Community.Relation;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class CommunityRelations {
    private final JdbcTemplate jdbc;
    private final CommunityScope scope;
    private final CommunityTargets targets;
    private final CommunityActivity activity;

    CommunityRelations(JdbcTemplate jdbc, CommunityScope scope, CommunityTargets targets, CommunityActivity activity) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.targets = targets;
        this.activity = activity;
    }

    void relate(AuthPrincipal actor, String space, UUID articleId, Relation relation, boolean enabled) {
        WorkspaceId workspace = targets.workspace(space);
        scope.published(actor, workspace, enabled, (identity, snapshot) -> {
            CommunityTargets.article(snapshot, articleId);
            boolean exists = has(identity.accountId(), workspace, articleId, relation);
            if (exists == enabled) {
                return null;
            }
            if (enabled) {
                CommunityActivity.capacity(
                        jdbc.queryForObject(
                                "select count(*) from community_relations where account_id=? and kind=?",
                                Long.class,
                                identity.accountId(),
                                relation.name()),
                        1000);
                activity.consume(identity.accountId(), "RELATION", 60, 3000);
                jdbc.update(
                        "insert into community_relations(account_id,workspace_id,article_id,kind) values (?,?,?,?) on conflict do nothing",
                        identity.accountId(),
                        workspace.value(),
                        articleId,
                        relation.name());
            } else {
                jdbc.update(
                        "delete from community_relations where account_id=? and workspace_id=? and article_id=? and kind=?",
                        identity.accountId(),
                        workspace.value(),
                        articleId,
                        relation.name());
            }
            return null;
        });
    }

    void follow(AuthPrincipal actor, String space, boolean enabled) {
        WorkspaceId workspace = targets.workspace(space);
        scope.published(actor, workspace, enabled, (identity, snapshot) -> {
            if (following(identity.accountId(), workspace) == enabled) {
                return null;
            }
            if (enabled) {
                CommunityActivity.capacity(
                        jdbc.queryForObject(
                                "select count(*) from community_follows where account_id=?",
                                Long.class,
                                identity.accountId()),
                        100);
                activity.consume(identity.accountId(), "RELATION", 60, 3000);
                jdbc.update(
                        "insert into community_follows(account_id,workspace_id) values (?,?) on conflict do nothing",
                        identity.accountId(),
                        workspace.value());
            } else {
                jdbc.update(
                        "delete from community_follows where account_id=? and workspace_id=?",
                        identity.accountId(),
                        workspace.value());
            }
            return null;
        });
    }

    boolean has(UUID actor, WorkspaceId workspace, UUID articleId, Relation relation) {
        return actor != null
                && Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from community_relations where account_id=? and workspace_id=? and article_id=? and kind=?)",
                        Boolean.class,
                        actor,
                        workspace.value(),
                        articleId,
                        relation.name()));
    }

    boolean following(UUID actor, WorkspaceId workspace) {
        return actor != null
                && Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from community_follows where account_id=? and workspace_id=?)",
                        Boolean.class,
                        actor,
                        workspace.value()));
    }

    long likes(WorkspaceId workspace, UUID articleId) {
        return jdbc.queryForObject(
                "select count(*) from community_relations where workspace_id=? and article_id=? and kind='LIKE'",
                Long.class,
                workspace.value(),
                articleId);
    }
}
