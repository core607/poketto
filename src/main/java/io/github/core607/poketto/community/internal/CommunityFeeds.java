package io.github.core607.poketto.community.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.community.Community.ArticleCard;
import io.github.core607.poketto.community.Community.Feed;
import io.github.core607.poketto.community.Community.FollowedSpace;
import io.github.core607.poketto.community.Community.Page;
import io.github.core607.poketto.community.Community.Relation;
import io.github.core607.poketto.community.Community.SavedArticle;
import io.github.core607.poketto.community.CommunityException;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.workspace.PublicationUnavailableException;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

final class CommunityFeeds {
    private static final Comparator<ArticleCard> ORDER = Comparator.comparing(ArticleCard::createdAt)
            .reversed()
            .thenComparing(ArticleCard::space)
            .thenComparing(ArticleCard::route);
    private final JdbcTemplate jdbc;
    private final CommunityScope scope;
    private final CommunityTargets targets;
    private final ObjectMapper json;
    private final Semaphore readers = new Semaphore(2);

    CommunityFeeds(JdbcTemplate jdbc, CommunityScope scope, CommunityTargets targets, ObjectMapper json) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.targets = targets;
        this.json = json;
    }

    Page<SavedArticle> saved(AuthPrincipal actor, long before, Relation kind) {
        List<SavedRow> rows = jdbc.query(
                "select position,workspace_id,article_id from community_relations where account_id=? and kind=? and position<? order by position desc limit 21",
                (row, number) -> new SavedRow(
                        row.getLong(1), new WorkspaceId(row.getObject(2, UUID.class)), row.getObject(3, UUID.class)),
                actor.accountId(),
                kind.name(),
                CommunityActivity.before(before));
        return CommunityActivity.page(
                rows,
                SavedRow::position,
                selected -> selected.stream()
                        .map(row -> new SavedArticle(
                                row.position(),
                                targets.card(row.workspace(), row.article()).orElse(null)))
                        .toList());
    }

    void removeSaved(AuthPrincipal actor, long position, Relation kind) {
        scope.personal(actor, identity -> {
            jdbc.update(
                    "delete from community_relations where position=? and account_id=? and kind=?",
                    position,
                    identity.accountId(),
                    kind.name());
            return null;
        });
    }

    void unfollow(AuthPrincipal actor, long position) {
        scope.personal(actor, identity -> {
            jdbc.update(
                    "delete from community_follows where position=? and account_id=?", position, identity.accountId());
            return null;
        });
    }

    List<FollowedSpace> following(AuthPrincipal actor) {
        List<FollowedSpace> result = new ArrayList<>();
        for (FollowRow row : followed(actor)) {
            try {
                WorkspacePublications.Publication publication = targets.publication(row.workspace());
                boolean available = publication.publiclyEnabled();
                if (available) {
                    targets.read(row.workspace(), snapshot -> null);
                }
                result.add(new FollowedSpace(
                        row.position(),
                        available ? publication.slug() : null,
                        available ? publication.displayName() : null,
                        available));
            } catch (ContentRepositoryException | PublicationUnavailableException unavailable) {
                result.add(new FollowedSpace(row.position(), null, null, false));
            }
        }
        return List.copyOf(result);
    }

    Feed feed(AuthPrincipal actor, String cursor) {
        if (!readers.tryAcquire()) {
            throw new CommunityException(CommunityException.Code.LIMIT_REACHED);
        }
        try {
            return readFeed(actor, cursor);
        } finally {
            readers.release();
        }
    }

    private Feed readFeed(AuthPrincipal actor, String cursor) {
        ArticleCard boundary = boundary(cursor);
        var budget = new FeedBudget();
        var candidates = new PriorityQueue<ArticleCard>(21, ORDER.reversed());
        for (FollowRow row : followed(actor)) {
            try {
                WorkspacePublications.Publication publication = targets.publication(row.workspace());
                List<ArticleCard> page = targets.read(row.workspace(), snapshot -> {
                    budget.consume(snapshot.articles().size());
                    return snapshot.articles().stream()
                            .map(article -> CommunityTargets.card(publication, article))
                            .filter(card -> boundary == null || ORDER.compare(card, boundary) > 0)
                            .sorted(ORDER)
                            .limit(21)
                            .toList();
                });
                for (ArticleCard card : page) {
                    candidates.add(card);
                    if (candidates.size() > 21) {
                        candidates.remove();
                    }
                }
            } catch (ContentRepositoryException | PublicationUnavailableException unavailable) {
                // An unavailable followed space contributes no distribution cards.
            }
        }
        List<ArticleCard> sorted = candidates.stream().sorted(ORDER).toList();
        List<ArticleCard> scanned = sorted.stream().limit(20).toList();
        List<ArticleCard> items = scanned.stream()
                .map(targets::currentCard)
                .flatMap(Optional::stream)
                .toList();
        budget.consume(0);
        return new Feed(items, sorted.size() > 20 ? cursor(scanned.getLast()) : null);
    }

    private static final class FeedBudget {
        private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        private int documents;

        void consume(int count) {
            documents += count;
            if (documents > 100_000 || System.nanoTime() >= deadline) {
                throw new CommunityException(CommunityException.Code.LIMIT_REACHED);
            }
        }
    }

    private List<FollowRow> followed(AuthPrincipal actor) {
        return jdbc.query(
                "select position,workspace_id from community_follows where account_id=? order by workspace_id limit 100",
                (row, number) -> new FollowRow(row.getLong(1), new WorkspaceId(row.getObject(2, UUID.class))),
                actor.accountId());
    }

    private ArticleCard boundary(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        if (cursor.length() > 2048) {
            throw new IllegalArgumentException("feed cursor exceeds its bound");
        }
        final Cursor decoded;
        try {
            decoded = json.readValue(
                    new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8), Cursor.class);
        } catch (JacksonException invalid) {
            throw new IllegalArgumentException("feed cursor is invalid", invalid);
        }
        if (decoded == null) {
            throw new IllegalArgumentException("feed cursor must contain an object");
        }
        return new ArticleCard(decoded.space(), "", null, decoded.route(), "", "", decoded.createdAt());
    }

    private String cursor(ArticleCard card) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.writeValueAsBytes(new Cursor(card.createdAt(), card.space(), card.route())));
    }

    record Cursor(Instant createdAt, String space, String route) {
        Cursor {
            if (createdAt == null || space == null || route == null) {
                throw new IllegalArgumentException("feed cursor fields are required");
            }
            if (space.length() > 64 || route.length() > 255) {
                throw new IllegalArgumentException("feed cursor fields exceed their bounds");
            }
        }
    }

    private record SavedRow(long position, WorkspaceId workspace, UUID article) {}

    private record FollowRow(long position, WorkspaceId workspace) {}
}
