package io.github.core607.poketto.community;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.CommunityAccounts.Profile;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Browser-account interactions resolved against current publication, never a stored article copy. */
public interface Community {
    SpaceState space(AuthPrincipal actor, String space);

    record SpaceState(boolean following, UUID accountId, boolean mayParticipate) {}

    Thread article(AuthPrincipal actor, String space, UUID articleId, UUID parentId, long before);

    void relate(AuthPrincipal actor, String space, UUID articleId, Relation relation, boolean enabled);

    void follow(AuthPrincipal actor, String space, boolean enabled);

    UUID comment(AuthPrincipal actor, String space, UUID articleId, CommentInput input);

    void deleteComment(AuthPrincipal actor, UUID commentId);

    void moderateComment(AuthPrincipal actor, UUID commentId);

    void report(AuthPrincipal actor, UUID commentId, ReportInput input);

    void block(AuthPrincipal actor, UUID accountId, boolean enabled);

    Page<SavedArticle> bookmarks(AuthPrincipal actor, long before);

    Page<SavedArticle> likes(AuthPrincipal actor, long before);

    void removeLike(AuthPrincipal actor, long position);

    void removeBookmark(AuthPrincipal actor, long position);

    void unfollow(AuthPrincipal actor, long position);

    Feed feed(AuthPrincipal actor, String cursor);

    List<FollowedSpace> following(AuthPrincipal actor);

    Page<Notification> notifications(AuthPrincipal actor, long before);

    void markRead(AuthPrincipal actor, List<Long> positions);

    Page<BlockedAccount> blocks(AuthPrincipal actor, long before);

    Page<Report> reports(AuthPrincipal actor, long before);

    void resolveReport(AuthPrincipal actor, long position, boolean remove);

    enum Relation {
        LIKE,
        BOOKMARK
    }

    record CommentInput(UUID requestId, UUID parentId, String body) {
        public CommentInput {
            Objects.requireNonNull(requestId, "comment request id is required");
            body = text(body, 4000, "comment");
        }
    }

    record ReportInput(String reason) {
        public ReportInput {
            reason = text(reason, 1000, "report reason");
        }
    }

    private static String text(String value, int maximum, String subject) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(subject + " must not be blank");
        }
        if (value.codePointCount(0, value.length()) > maximum || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(subject + " exceeds its text bounds");
        }
        for (int i = 0; i < value.length(); i++) {
            char unit = value.charAt(i);
            if (Character.isHighSurrogate(unit)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException(subject + " contains invalid Unicode");
                }
            } else if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException(subject + " contains invalid Unicode");
            }
        }
        return value.strip();
    }

    record Page<T>(List<T> items, Long nextBefore) {
        public Page {
            items = List.copyOf(items);
        }
    }

    record Thread(
            long likes,
            boolean liked,
            boolean bookmarked,
            boolean following,
            UUID accountId,
            boolean mayParticipate,
            boolean mayModerate,
            Page<Comment> comments) {}

    record Comment(
            UUID id,
            long position,
            UUID parentId,
            Profile author,
            String body,
            Instant createdAt,
            boolean deleted,
            long replies,
            boolean mayDelete) {}

    record ArticleCard(
            String space,
            String spaceName,
            UUID articleId,
            String route,
            String title,
            String authorName,
            Instant createdAt) {}

    record SavedArticle(long position, ArticleCard article) {}

    record FollowedSpace(long position, String space, String displayName, boolean available) {}

    record Feed(List<ArticleCard> items, String nextCursor) {
        public Feed {
            items = List.copyOf(items);
        }
    }

    /**
     * Either a comment, or a correction event: {@code PROPOSED} for owners, {@code ACCEPTED},
     * {@code DECLINED} or {@code STALE} for the proposer. The other subject's fields are null.
     */
    record Notification(
            long position,
            UUID commentId,
            Profile actor,
            String excerpt,
            ArticleCard article,
            Instant createdAt,
            boolean read,
            UUID correctionId,
            String event) {}

    record BlockedAccount(long position, Profile account) {}

    record Report(
            long position,
            UUID commentId,
            Profile reporter,
            Profile author,
            String reason,
            String commentBody,
            Instant createdAt,
            String status) {}
}
