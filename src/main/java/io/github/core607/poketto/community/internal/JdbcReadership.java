package io.github.core607.poketto.community.internal;

import io.github.core607.poketto.community.CommunityException;
import io.github.core607.poketto.community.Readership;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.workspace.PublicationUnavailableException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Daily reader counts per space and logical route; see {@link ReaderDigests} for what is remembered. */
final class JdbcReadership implements Readership {
    private static final Logger log = LoggerFactory.getLogger(JdbcReadership.class);
    /** Longer routes cannot be public articles, so they are rejected before any snapshot read. */
    static final int MAX_ROUTE = 2048;

    private static final Pattern CRAWLER = Pattern.compile(
            "bot|crawl|spider|slurp|fetch|preview|scan|monitor|headless|curl|wget|python|httpclient|okhttp"
                    + "|go-http|java/|facebookexternalhit|embedly|whatsapp",
            Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbc;
    private final CommunityTargets targets;
    private final ReaderDigests seen;
    private final Clock clock;

    JdbcReadership(JdbcTemplate jdbc, CommunityTargets targets, ReaderDigests seen, Clock clock) {
        this.jdbc = jdbc;
        this.targets = targets;
        this.seen = seen;
        this.clock = clock;
    }

    @Override
    public void record(String space, String route, String client, String userAgent) {
        if (crawler(userAgent) || !plausible(route) || client == null) {
            return;
        }
        LocalDate day = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        // Per-address admission comes first, so rotating user agents cannot force snapshot scans or writes.
        if (!seen.admit(day, client)) {
            return;
        }
        try {
            WorkspaceId workspace = targets.workspace(space);
            if (published(workspace, route) && seen.first(day, client, userAgent, space, route)) {
                store(day, workspace, route, client, userAgent, space);
            }
        } catch (CommunityException | ContentRepositoryException | PublicationUnavailableException unavailable) {
            // An unavailable space or snapshot simply counts nothing; the reader learns nothing either way.
        }
    }

    private void store(
            LocalDate day, WorkspaceId workspace, String route, String client, String userAgent, String space) {
        try {
            jdbc.update("""
                    insert into article_views(workspace_id,route,day,views) values (?,?,?,1)
                    on conflict (workspace_id,route,day) do update set views=article_views.views+1
                    """, workspace.value(), route, day);
        } catch (DataAccessException failure) {
            // The reader was never counted, so a later report from them may count; the answer stays 204.
            seen.forget(day, client, userAgent, space, route);
            log.warn("Reader count could not be stored", failure);
        }
    }

    @Override
    public OptionalLong total(String space, String route) {
        if (!plausible(route)) {
            return OptionalLong.empty();
        }
        try {
            WorkspaceId workspace = targets.workspace(space);
            if (!published(workspace, route)) {
                return OptionalLong.empty();
            }
            Long views = jdbc.queryForObject(
                    "select coalesce(sum(views),0) from article_views where workspace_id=? and route=?",
                    Long.class,
                    workspace.value(),
                    route);
            return OptionalLong.of(views == null ? 0 : views);
        } catch (CommunityException | ContentRepositoryException | PublicationUnavailableException unavailable) {
            return OptionalLong.empty();
        }
    }

    private boolean published(WorkspaceId workspace, String route) {
        return targets.read(
                workspace,
                snapshot -> snapshot.articles().stream()
                        .anyMatch(article -> article.route().equals(route)));
    }

    private static boolean crawler(String userAgent) {
        return userAgent == null
                || userAgent.isBlank()
                || CRAWLER.matcher(userAgent).find();
    }

    private static boolean plausible(String route) {
        return route != null && route.startsWith("/") && route.length() <= MAX_ROUTE;
    }
}
