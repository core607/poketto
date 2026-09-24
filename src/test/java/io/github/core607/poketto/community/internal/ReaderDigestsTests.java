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
        var seen = new ReaderDigests(10, () -> new byte[] {1});

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
        var seen = new ReaderDigests(10, () -> new byte[] {(byte) salts.incrementAndGet()});

        assertThat(seen.first(today, "client", "/a")).isTrue();
        assertThat(seen.first(today.plusDays(1), "client", "/a")).isTrue();
        assertThat(salts).hasValue(2);
        assertThat(seen.first(today.plusDays(1), "client", "/a")).isFalse();
    }

    @Test
    void aFullDayCountsNoFurtherReaders() {
        var seen = new ReaderDigests(2, () -> new byte[] {1});

        assertThat(seen.first(today, "first")).isTrue();
        assertThat(seen.first(today, "second")).isTrue();
        assertThat(seen.first(today, "third")).isFalse();
        assertThat(seen.first(today.plusDays(1), "third")).isTrue();
        assertThatThrownBy(() -> new ReaderDigests(0, () -> new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
    }
}
