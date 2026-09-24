package io.github.core607.poketto.community.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReaderDigestsTests {
    private final LocalDate today = LocalDate.of(2026, 9, 24);

    @Test
    void aClientCountsOncePerArticleAndDay() {
        var seen = new ReaderDigests(10, 10, () -> new byte[] {1});

        assertThat(seen.first(today, "client", "agent", "home", "/a")).isTrue();
        assertThat(seen.first(today, "client", "agent", "home", "/a")).isFalse();
        assertThat(seen.first(today, "client", "agent", "home", "/b")).isTrue();
        assertThat(seen.first(today, "other", "agent", "home", "/a")).isTrue();
        // Length-prefixed parts keep shifted boundaries apart.
        assertThat(seen.first(today, "client", "agent", "home/", "a")).isTrue();
    }

    @Test
    void aNewDayReplacesTheSaltAndForgetsEveryDigest() {
        var salts = new AtomicInteger();
        var seen = new ReaderDigests(10, 10, () -> new byte[] {(byte) salts.incrementAndGet()});

        assertThat(seen.first(today, "client", "/a")).isTrue();
        assertThat(seen.first(today.plusDays(1), "client", "/a")).isTrue();
        assertThat(salts).hasValue(2);
        assertThat(seen.first(today.plusDays(1), "client", "/a")).isFalse();
    }

    @Test
    void aFullDayCountsNoFurtherReaders() {
        var seen = new ReaderDigests(2, 10, () -> new byte[] {1});

        assertThat(seen.first(today, "first")).isTrue();
        assertThat(seen.first(today, "second")).isTrue();
        assertThat(seen.first(today, "third")).isFalse();
        assertThat(seen.first(today.plusDays(1), "third")).isTrue();
        assertThatThrownBy(() -> new ReaderDigests(0, 10, () -> new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
    }

    @Test
    void anAddressIsAdmittedALimitedNumberOfTimesADay() {
        var seen = new ReaderDigests(2, 3, () -> new byte[] {1});

        assertThat(seen.admit(today, "203.0.113.5")).isTrue();
        assertThat(seen.admit(today, "203.0.113.5")).isTrue();
        assertThat(seen.admit(today, "203.0.113.5")).isTrue();
        assertThat(seen.admit(today, "203.0.113.5")).isFalse();
        assertThat(seen.admit(today, "198.51.100.9")).isTrue();
        // Two addresses fill this day's capacity; a third is refused until tomorrow.
        assertThat(seen.admit(today, "192.0.2.1")).isFalse();
        assertThat(seen.admit(today.plusDays(1), "192.0.2.1")).isTrue();
        assertThat(seen.admit(today.plusDays(1), "203.0.113.5")).isTrue();
    }

    @Test
    void aForgottenReadCountsAgain() {
        var seen = new ReaderDigests(10, 10, () -> new byte[] {1});

        assertThat(seen.first(today, "client", "/a")).isTrue();
        seen.forget(today, "client", "/a");
        assertThat(seen.first(today, "client", "/a")).isTrue();
        // A stale day's forget has nothing to remove.
        seen.forget(today.minusDays(1), "client", "/a");
        assertThat(seen.first(today, "client", "/a")).isFalse();
    }
}
