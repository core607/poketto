package io.github.core607.poketto.auth;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reservations count failed sends too, preventing a broken provider from permitting unbounded retries. */
final class EmailSendLimits {
    private final JdbcTemplate jdbc;
    private final EmailChallengeDigests digests;
    private final int dailyLimit;

    EmailSendLimits(JdbcTemplate jdbc, EmailChallengeDigests digests, int dailyLimit) {
        if (dailyLimit < 1 || dailyLimit > 100_000) {
            throw new IllegalArgumentException("Email daily send limit must be between 1 and 100000");
        }
        this.jdbc = jdbc;
        this.digests = digests;
        this.dailyLimit = dailyLimit;
    }

    void reserve(String email, String address, Instant now) {
        Instant day = now.truncatedTo(ChronoUnit.DAYS);
        take("installation", day, Duration.ofDays(1), dailyLimit, Duration.ZERO, now);
        // Every reservation holds the installation row first, including cleanup and per-email cooldowns.
        jdbc.update("delete from auth_email_limits where expires_at<?", Timestamp.from(now.minus(Duration.ofDays(1))));
        jdbc.update(
                "delete from auth_email_challenges where expires_at<?", Timestamp.from(now.minus(Duration.ofDays(1))));
        take("email:" + digests.of(email), day, Duration.ofDays(1), 10, Duration.ofSeconds(60), now);
        String source = address == null || address.length() > 128 ? "unknown" : address;
        take(
                "address:" + digests.of(source),
                now.truncatedTo(ChronoUnit.HOURS),
                Duration.ofHours(1),
                20,
                Duration.ZERO,
                now);
    }

    private void take(String key, Instant start, Duration window, int limit, Duration cooldown, Instant now) {
        int changed = jdbc.update(
                "insert into auth_email_limits(bucket,window_start,expires_at,send_count,last_sent_at) values (?,?,?,1,?) "
                        + "on conflict(bucket) do update set window_start=excluded.window_start,expires_at=excluded.expires_at,"
                        + "send_count=case when auth_email_limits.window_start=excluded.window_start then auth_email_limits.send_count+1 else 1 end,"
                        + "last_sent_at=excluded.last_sent_at where "
                        + "(auth_email_limits.window_start<>excluded.window_start or auth_email_limits.send_count<?) "
                        + "and auth_email_limits.last_sent_at<=?",
                key,
                Timestamp.from(start),
                Timestamp.from(start.plus(window)),
                Timestamp.from(now),
                limit,
                Timestamp.from(now.minus(cooldown)));
        if (changed != 1) {
            throw new EmailChallengeException(EmailChallengeException.Code.RATE_LIMITED);
        }
    }
}
