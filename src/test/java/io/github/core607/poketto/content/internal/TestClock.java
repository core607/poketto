package io.github.core607.poketto.content.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Hands every read a later instant, so a test never depends on host clock resolution to prove that
 * a write advanced {@code updated_at}.
 */
final class TestClock extends Clock {

    private Instant next = Instant.parse("2026-08-31T10:00:00Z");

    @Override
    public Instant instant() {
        Instant current = next;
        next = next.plusSeconds(60);
        return current;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
