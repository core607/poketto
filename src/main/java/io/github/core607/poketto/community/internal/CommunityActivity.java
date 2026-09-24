package io.github.core607.poketto.community.internal;

import io.github.core607.poketto.community.CommunityException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class CommunityActivity {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    CommunityActivity(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    void consume(UUID actor, String action, int minuteLimit, int dailyLimit) {
        consumeWindow(actor, action + "_MINUTE", 60, minuteLimit);
        consumeWindow(actor, action + "_DAY", 86400, dailyLimit);
    }

    private void consumeWindow(UUID actor, String action, int seconds, int maximum) {
        long window = clock.instant().getEpochSecond() / seconds;
        int changed = jdbc.update(
                "insert into community_rate_limits(account_id,action,window_start,used) values (?,?,?,1) "
                        + "on conflict(account_id,action) do update set window_start=excluded.window_start, "
                        + "used=case when community_rate_limits.window_start=excluded.window_start then community_rate_limits.used+1 else 1 end "
                        + "where community_rate_limits.window_start<>excluded.window_start or community_rate_limits.used<?",
                actor,
                action,
                window,
                maximum);
        if (changed != 1) {
            throw new CommunityException(CommunityException.Code.LIMIT_REACHED);
        }
    }

    /**
     * Adds one notification per recipient about either a comment or a correction event, skipping
     * the actor and anyone blocked either way, and keeps each inbox to its newest 1,000 rows.
     */
    void deliver(UUID actor, List<UUID> recipients, UUID comment, UUID correction, String event) {
        for (UUID recipient : recipients) {
            if (recipient.equals(actor) || blocked(actor, recipient)) {
                continue;
            }
            // Separate from account locks: recovery may hold the recipient while awaiting this workspace.
            jdbc.queryForObject(
                    "select pg_advisory_xact_lock(hashtextextended('community-inbox:' || ?::text,0))",
                    Object.class,
                    recipient);
            jdbc.update(
                    "insert into community_notifications(recipient_id,comment_id,correction_id,event) values (?,?,?,?) on conflict do nothing",
                    recipient,
                    comment,
                    correction,
                    event);
            jdbc.update(
                    "delete from community_notifications where recipient_id=? and position < coalesce((select position from community_notifications where recipient_id=? order by position desc offset 999 limit 1),0)",
                    recipient,
                    recipient);
        }
    }

    boolean blocked(UUID first, UUID second) {
        if (first == null || second == null) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from community_blocks where (blocker_id=? and blocked_id=?) or (blocker_id=? and blocked_id=?))",
                Boolean.class,
                first,
                second,
                second,
                first));
    }

    static void capacity(long used, long maximum) {
        if (used >= maximum) {
            throw new CommunityException(CommunityException.Code.LIMIT_REACHED);
        }
    }

    static long before(long before) {
        if (before < 0) {
            throw new IllegalArgumentException("community cursor must not be negative");
        }
        return before == 0 ? Long.MAX_VALUE : before;
    }
}
