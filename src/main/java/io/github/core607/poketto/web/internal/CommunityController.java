package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.community.Community;
import io.github.core607.poketto.community.CommunityException;
import io.github.core607.poketto.community.Readership;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.workspace.PublicationUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class CommunityController {
    private static final String PRIVATE = "/api/auth/community";
    private static final String ARTICLE = PRIVATE + "/spaces/{space}/articles/{articleId}";
    private static final String VIEWS = "/api/public/community/spaces/{space}/views";
    private final Community community;
    private final Readership readership;

    CommunityController(Community community, Readership readership) {
        this.community = community;
        this.readership = readership;
    }

    /** Anonymous and CSRF-exempt: it changes no account state, and answers alike whether it counted. */
    @PostMapping(VIEWS)
    ResponseEntity<Void> view(@PathVariable String space, @RequestParam String route, HttpServletRequest request) {
        readership.record(space, route, request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping(VIEWS)
    ResponseEntity<Views> views(@PathVariable String space, @RequestParam String route) {
        long views = readership
                .total(space, route)
                .orElseThrow(() -> new CommunityException(CommunityException.Code.UNAVAILABLE));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Views(views));
    }

    @GetMapping("/api/public/community/spaces/{space}/articles/{articleId}")
    ResponseEntity<Community.Thread> article(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable String space,
            @PathVariable UUID articleId,
            @RequestParam(required = false) UUID parentId,
            @RequestParam(defaultValue = "0") long before) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(community.article(actor, space, articleId, parentId, before));
    }

    @PutMapping(ARTICLE + "/relations/{relation}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void relate(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable String space,
            @PathVariable UUID articleId,
            @PathVariable Community.Relation relation,
            @RequestBody Toggle input) {
        community.relate(actor, space, articleId, relation, input.enabled());
    }

    @GetMapping("/api/public/community/spaces/{space}")
    ResponseEntity<Community.SpaceState> space(
            @AuthenticationPrincipal AuthPrincipal actor, @PathVariable String space) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(community.space(actor, space));
    }

    @PutMapping(PRIVATE + "/spaces/{space}/following")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void follow(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable String space, @RequestBody Toggle input) {
        community.follow(actor, space, input.enabled());
    }

    @PostMapping(ARTICLE + "/comments")
    Created comment(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable String space,
            @PathVariable UUID articleId,
            @RequestBody Community.CommentInput input) {
        return new Created(community.comment(actor, space, articleId, input));
    }

    @DeleteMapping(PRIVATE + "/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable UUID commentId) {
        community.deleteComment(actor, commentId);
    }

    @PostMapping(PRIVATE + "/comments/{commentId}/reports")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void report(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable UUID commentId,
            @RequestBody Community.ReportInput input) {
        community.report(actor, commentId, input);
    }

    @DeleteMapping(PRIVATE + "/comments/{commentId}/moderation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void moderate(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable UUID commentId) {
        community.moderateComment(actor, commentId);
    }

    @PutMapping(PRIVATE + "/blocks/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void block(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable UUID accountId, @RequestBody Toggle input) {
        community.block(actor, accountId, input.enabled());
    }

    @GetMapping(PRIVATE + "/bookmarks")
    Community.Page<Community.SavedArticle> bookmarks(
            @AuthenticationPrincipal AuthPrincipal actor, @RequestParam(defaultValue = "0") long before) {
        return community.bookmarks(actor, before);
    }

    @DeleteMapping(PRIVATE + "/bookmarks/{position}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeBookmark(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable long position) {
        community.removeBookmark(actor, position);
    }

    @GetMapping(PRIVATE + "/following")
    List<Community.FollowedSpace> following(@AuthenticationPrincipal AuthPrincipal actor) {
        return community.following(actor);
    }

    @GetMapping(PRIVATE + "/likes")
    Community.Page<Community.SavedArticle> likes(
            @AuthenticationPrincipal AuthPrincipal actor, @RequestParam(defaultValue = "0") long before) {
        return community.likes(actor, before);
    }

    @DeleteMapping(PRIVATE + "/likes/{position}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeLike(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable long position) {
        community.removeLike(actor, position);
    }

    @DeleteMapping(PRIVATE + "/following/{position}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unfollow(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable long position) {
        community.unfollow(actor, position);
    }

    @GetMapping(PRIVATE + "/feed")
    Community.Feed feed(@AuthenticationPrincipal AuthPrincipal actor, @RequestParam(required = false) String cursor) {
        return community.feed(actor, cursor);
    }

    @GetMapping(PRIVATE + "/notifications")
    Community.Page<Community.Notification> notifications(
            @AuthenticationPrincipal AuthPrincipal actor, @RequestParam(defaultValue = "0") long before) {
        return community.notifications(actor, before);
    }

    @PostMapping(PRIVATE + "/notifications/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markRead(@AuthenticationPrincipal AuthPrincipal actor, @RequestBody ReadNotifications input) {
        community.markRead(actor, input.positions());
    }

    @GetMapping(PRIVATE + "/blocks")
    Community.Page<Community.BlockedAccount> blocks(
            @AuthenticationPrincipal AuthPrincipal actor, @RequestParam(defaultValue = "0") long before) {
        return community.blocks(actor, before);
    }

    @GetMapping(PRIVATE + "/reports")
    Community.Page<Community.Report> reports(
            @AuthenticationPrincipal AuthPrincipal actor, @RequestParam(defaultValue = "0") long before) {
        return community.reports(actor, before);
    }

    @PostMapping(PRIVATE + "/reports/{position}/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resolve(
            @AuthenticationPrincipal AuthPrincipal actor, @PathVariable long position, @RequestBody Resolution input) {
        community.resolveReport(actor, position, input.remove());
    }

    @ExceptionHandler(CommunityException.class)
    ProblemDetail failure(CommunityException failure) {
        return problem(failure);
    }

    static ProblemDetail problem(CommunityException failure) {
        HttpStatus status =
                switch (failure.code()) {
                    case UNAVAILABLE -> HttpStatus.NOT_FOUND;
                    case PARTICIPATION_REQUIRED, DENIED -> HttpStatus.FORBIDDEN;
                    case LIMIT_REACHED -> HttpStatus.TOO_MANY_REQUESTS;
                    case REQUEST_CONFLICT, REPLY_UNAVAILABLE, BASE_CHANGED -> HttpStatus.CONFLICT;
                };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Community operation could not be completed");
        problem.setProperty("code", "COMMUNITY_" + failure.code());
        return problem;
    }

    @ExceptionHandler({ContentRepositoryException.class, PublicationUnavailableException.class})
    ProblemDetail unavailable() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Public article is unavailable");
    }

    record Views(long views) {}

    record Toggle(Boolean enabled) {
        Toggle {
            if (enabled == null) {
                throw new IllegalArgumentException("enabled is required");
            }
        }
    }

    record Resolution(Boolean remove) {
        Resolution {
            if (remove == null) {
                throw new IllegalArgumentException("remove is required");
            }
        }
    }

    record ReadNotifications(List<Long> positions) {
        ReadNotifications {
            if (positions == null || positions.isEmpty() || positions.size() > 50) {
                throw new IllegalArgumentException("one to fifty notification positions are required");
            }
            if (positions.stream().anyMatch(position -> position == null || position <= 0)) {
                throw new IllegalArgumentException("notification positions must be positive");
            }
            positions = List.copyOf(positions);
        }
    }

    record Created(UUID commentId) {}
}
