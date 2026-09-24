package io.github.core607.poketto.community.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.CommunityAccounts;
import io.github.core607.poketto.auth.CommunityAccounts.Profile;
import io.github.core607.poketto.community.Community.Page;
import io.github.core607.poketto.community.CommunityException;
import io.github.core607.poketto.community.Corrections;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.PrincipalType;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.ReviewedBodyEdits;
import io.github.core607.poketto.content.WritePrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

final class CommunityCorrections implements Corrections {
    private static final String COLUMNS =
            "correction_id,position,workspace_id,route,author_id,base_digest,proposed_body,reason,status,created_at,resolved_at,resolver_id";
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final CommunityAccounts accounts;
    private final CommunityScope scope;
    private final CommunityTargets targets;
    private final CommunityActivity activity;
    private final ReviewedBodyEdits edits;

    CommunityCorrections(
            JdbcTemplate jdbc,
            AuthService auth,
            CommunityAccounts accounts,
            CommunityScope scope,
            CommunityTargets targets,
            CommunityActivity activity,
            ReviewedBodyEdits edits) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.accounts = accounts;
        this.scope = scope;
        this.targets = targets;
        this.activity = activity;
        this.edits = edits;
    }

    @Override
    public UUID propose(AuthPrincipal actor, String space, Proposal proposal) {
        WorkspaceId workspace = targets.workspace(space);
        return scope.published(actor, workspace, true, (identity, snapshot) -> {
            PublicArticle article = served(snapshot, proposal.route()).orElseThrow(CommunityTargets::unavailable);
            if (!digest(article.body()).equals(proposal.baseDigest())) {
                throw new CommunityException(CommunityException.Code.BASE_CHANGED);
            }
            if (digest(proposal.body()).equals(proposal.baseDigest())) {
                throw new IllegalArgumentException("a correction must change the body");
            }
            List<UUID> owners = accounts.owners(workspace);
            if (owners.stream().anyMatch(owner -> activity.blocked(identity.accountId(), owner))) {
                throw new CommunityException(CommunityException.Code.DENIED);
            }
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from community_corrections where author_id=? and workspace_id=? and route=? and status in ('OPEN','ACCEPTING'))",
                    Boolean.class,
                    identity.accountId(),
                    workspace.value(),
                    proposal.route()))) {
                throw new CommunityException(CommunityException.Code.REQUEST_CONFLICT);
            }
            activity.consume(identity.accountId(), "CORRECTION", 5, 30);
            // Resolved text leaves the database after 90 days; the row keeps credit and inbox history.
            jdbc.update(
                    "update community_corrections set proposed_body='',reason='' where status<>'OPEN' and proposed_body<>'' and resolved_at<current_timestamp - interval '90 days'");
            UUID id = UUID.randomUUID();
            jdbc.update(
                    "insert into community_corrections(correction_id,workspace_id,route,author_id,base_digest,proposed_body,reason,credited) values (?,?,?,?,?,?,?,?)",
                    id,
                    workspace.value(),
                    proposal.route(),
                    identity.accountId(),
                    proposal.baseDigest(),
                    proposal.body(),
                    proposal.reason(),
                    proposal.credited());
            activity.deliver(identity.accountId(), owners, null, id, "PROPOSED");
            return id;
        });
    }

    @Override
    public Optional<Mine> mine(AuthPrincipal actor, String space, String route) {
        WorkspaceId workspace = targets.workspace(space);
        return scope.personal(
                actor,
                identity -> jdbc
                        .query(
                                "select correction_id,status,created_at from community_corrections where author_id=? and workspace_id=? and route=? order by position desc limit 1",
                                (row, number) -> new Mine(
                                        row.getObject(1, UUID.class),
                                        row.getString(2),
                                        row.getTimestamp(3).toInstant()),
                                identity.accountId(),
                                workspace.value(),
                                route)
                        .stream()
                        .findFirst());
    }

    @Override
    public void withdraw(AuthPrincipal actor, UUID correctionId) {
        scope.personal(actor, identity -> {
            int changed = jdbc.update(
                    "update community_corrections set status='WITHDRAWN',resolved_at=current_timestamp where correction_id=? and author_id=? and status='OPEN'",
                    correctionId,
                    identity.accountId());
            if (changed != 1) {
                throw conflict(find(correctionId).filter(row -> row.author().equals(identity.accountId())));
            }
            return null;
        });
    }

    @Override
    public List<String> credits(String space, String route) {
        WorkspaceId workspace = targets.workspace(space);
        targets.read(workspace, snapshot -> served(snapshot, route).orElseThrow(CommunityTargets::unavailable));
        List<UUID> authors = jdbc.query(
                "select author_id from community_corrections where workspace_id=? and route=? and status='ACCEPTED' and credited group by author_id order by min(resolved_at) limit 20",
                (row, number) -> row.getObject(1, UUID.class),
                workspace.value(),
                route);
        List<UUID> owners = accounts.owners(workspace);
        // Someone who has since blocked the owner, or the other way round, is no longer named.
        List<UUID> credited = authors.stream()
                .filter(author -> owners.stream().noneMatch(owner -> activity.blocked(author, owner)))
                .toList();
        Map<UUID, Profile> profiles = accounts.profiles(new LinkedHashSet<>(credited));
        return credited.stream()
                .map(profiles::get)
                .filter(profile -> profile != null)
                .map(Profile::displayName)
                .toList();
    }

    @Override
    public Page<Review> open(AuthPrincipal actor, WorkspaceId workspace, long before) {
        auth.authorize(actor, workspace, Capability.PUBLISH);
        List<Row> rows = jdbc.query(
                "select " + COLUMNS
                        + " from community_corrections where workspace_id=? and status='OPEN' and position<? order by position desc limit 21",
                CommunityCorrections::row,
                workspace.value(),
                CommunityActivity.before(before));
        List<Row> selected = rows.stream().limit(20).toList();
        Map<UUID, Profile> profiles =
                accounts.profiles(selected.stream().map(Row::author).collect(Collectors.toSet()));
        String space = targets.publication(workspace).slug();
        List<Review> reviews = targets.read(
                workspace,
                snapshot -> selected.stream()
                        .map(row -> {
                            Optional<PublicArticle> article = served(snapshot, row.route());
                            return new Review(
                                    row.id(),
                                    row.position(),
                                    space,
                                    row.route(),
                                    article.map(PublicArticle::title).orElse(row.route()),
                                    profiles.get(row.author()),
                                    row.reason(),
                                    row.body(),
                                    article.map(PublicArticle::body).orElse(""),
                                    article.map(PublicArticle::body)
                                            .map(CommunityCorrections::digest)
                                            .filter(row.base()::equals)
                                            .isEmpty(),
                                    row.createdAt());
                        })
                        .toList());
        return new Page<>(reviews, rows.size() > 20 ? selected.getLast().position() : null);
    }

    @Override
    public Resolution accept(AuthPrincipal actor, WorkspaceId workspace, UUID correctionId) {
        auth.authorize(actor, workspace, Capability.PUBLISH);
        Row row = claim(actor, workspace, correctionId);
        ReviewedBodyEdits.Outcome outcome;
        try {
            // Git is written outside any transaction; the ACCEPTING claim keeps decline and withdrawal out.
            outcome = edits.replaceBody(
                    actor,
                    workspace,
                    row.route(),
                    row.base(),
                    row.body(),
                    new WritePrincipal(PrincipalType.ACCOUNT, row.author().toString()));
        } catch (RuntimeException failure) {
            jdbc.update(
                    "update community_corrections set status='OPEN',claimed_at=null,resolver_id=null where correction_id=? and status='ACCEPTING' and resolver_id=?",
                    row.id(),
                    row.resolver());
            throw failure;
        }
        boolean stale = outcome.result() == ReviewedBodyEdits.Result.STALE;
        resolve(
                actor,
                workspace,
                row,
                stale ? "STALE" : "ACCEPTED",
                outcome.commit().orElse(null));
        return stale ? Resolution.STALE : Resolution.ACCEPTED;
    }

    @Override
    public void decline(AuthPrincipal actor, WorkspaceId workspace, UUID correctionId) {
        auth.authorize(actor, workspace, Capability.PUBLISH);
        Row row = find(correctionId)
                .filter(found -> found.workspace().equals(workspace))
                .orElseThrow(CommunityTargets::unavailable);
        resolve(actor, workspace, row, "DECLINED", null);
    }

    /**
     * Moves an open proposal, or one whose claim has been stranded for ten minutes, to ACCEPTING for
     * this reviewer. A proposal being accepted or already resolved is a conflict, never a silent success.
     */
    private Row claim(AuthPrincipal actor, WorkspaceId workspace, UUID correctionId) {
        return scope.personal(actor, identity -> {
            int claimed = jdbc.update(
                    "update community_corrections set status='ACCEPTING',claimed_at=current_timestamp,resolver_id=? where correction_id=? and workspace_id=? and (status='OPEN' or (status='ACCEPTING' and claimed_at<current_timestamp - interval '10 minutes'))",
                    identity.accountId(),
                    correctionId,
                    workspace.value());
            if (claimed != 1) {
                throw conflict(find(correctionId).filter(row -> row.workspace().equals(workspace)));
            }
            return find(correctionId).orElseThrow(CommunityTargets::unavailable);
        });
    }

    /** Declines an open proposal or settles this reviewer's own claim; any other state conflicts. */
    private void resolve(AuthPrincipal actor, WorkspaceId workspace, Row row, String status, String commit) {
        scope.personal(actor, identity -> {
            int changed = jdbc.update(
                    "update community_corrections set status=?,claimed_at=null,resolved_at=current_timestamp,resolver_id=?,commit_id=? where correction_id=? and workspace_id=? and (status='OPEN' and ?='DECLINED' or status='ACCEPTING' and ?<>'DECLINED' and resolver_id=?)",
                    status,
                    identity.accountId(),
                    commit,
                    row.id(),
                    workspace.value(),
                    status,
                    status,
                    identity.accountId());
            if (changed != 1) {
                throw conflict(Optional.of(row));
            }
            activity.deliver(identity.accountId(), List.of(row.author()), null, row.id(), status);
            return null;
        });
    }

    private static CommunityException conflict(Optional<Row> row) {
        return new CommunityException(
                row.isPresent() ? CommunityException.Code.REQUEST_CONFLICT : CommunityException.Code.UNAVAILABLE);
    }

    Optional<Row> find(UUID correctionId) {
        return jdbc
                .query(
                        "select " + COLUMNS + " from community_corrections where correction_id=?",
                        CommunityCorrections::row,
                        correctionId)
                .stream()
                .findFirst();
    }

    static Optional<PublicArticle> served(PublicContentSnapshot snapshot, String route) {
        return snapshot.articles().stream()
                .filter(article -> article.route().equals(route))
                .findFirst();
    }

    static String digest(String body) {
        return DocumentRevision.sha256(body.getBytes(StandardCharsets.UTF_8)).value();
    }

    private static Row row(ResultSet row, int number) throws SQLException {
        return new Row(
                row.getObject("correction_id", UUID.class),
                row.getLong("position"),
                new WorkspaceId(row.getObject("workspace_id", UUID.class)),
                row.getString("route"),
                row.getObject("author_id", UUID.class),
                row.getString("base_digest"),
                row.getString("proposed_body"),
                row.getString("reason"),
                row.getString("status"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("resolved_at") == null
                        ? null
                        : row.getTimestamp("resolved_at").toInstant(),
                row.getObject("resolver_id", UUID.class));
    }

    record Row(
            UUID id,
            long position,
            WorkspaceId workspace,
            String route,
            UUID author,
            String base,
            String body,
            String reason,
            String status,
            Instant createdAt,
            Instant resolvedAt,
            UUID resolver) {}
}
