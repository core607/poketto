package io.github.core607.poketto.capture.internal;

import io.github.core607.poketto.capture.CaptureLimitException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-sender minute and UTC-day windows, held in memory for the single instance. When the table is
 * full, new senders are refused rather than letting the table grow.
 */
final class CaptureLimits {
    private final int perMinute;
    private final int perDay;
    private final int maxSenders;
    private final Clock clock;
    private final Map<UUID, Usage> senders = new HashMap<>();

    CaptureLimits(int perMinute, int perDay, int maxSenders, Clock clock) {
        this.perMinute = perMinute;
        this.perDay = perDay;
        this.maxSenders = maxSenders;
        this.clock = clock;
    }

    synchronized void consume(UUID sender) {
        Instant now = clock.instant();
        Instant minute = now.truncatedTo(ChronoUnit.MINUTES);
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (!senders.containsKey(sender) && senders.size() >= maxSenders) {
            senders.values().removeIf(usage -> !usage.day().equals(day));
            if (senders.size() >= maxSenders) {
                throw new CaptureLimitException();
            }
        }
        Usage usage = senders.getOrDefault(sender, new Usage(minute, 0, day, 0));
        int inMinute = usage.minute().equals(minute) ? usage.inMinute() : 0;
        int inDay = usage.day().equals(day) ? usage.inDay() : 0;
        if (inMinute >= perMinute || inDay >= perDay) {
            throw new CaptureLimitException();
        }
        senders.put(sender, new Usage(minute, inMinute + 1, day, inDay + 1));
    }

    private record Usage(Instant minute, int inMinute, LocalDate day, int inDay) {}
}
