package io.github.core607.poketto.content;

import java.time.Duration;
import java.util.Objects;

/** Bounds a private baseline traversal; the destination must separately bound its encoded output. */
public record RepositoryBaselineLimits(int entries, long textBytes, Duration timeout) {
    public RepositoryBaselineLimits {
        if (entries < 1 || entries > 100_000) {
            throw new IllegalArgumentException("baseline entry limit must be between 1 and 100000");
        }
        if (textBytes < 0 || textBytes > 1024L * 1024 * 1024) {
            throw new IllegalArgumentException("baseline text limit must be between zero and 1 GiB");
        }
        Objects.requireNonNull(timeout, "baseline timeout must be present");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("baseline timeout must be positive and at most two minutes");
        }
    }
}
