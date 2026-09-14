package io.github.core607.poketto.executor.internal;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/** Native fixtures must place this directory on their size-bounded XFS pool. */
final class AccountCopyTestData {
    private AccountCopyTestData() {}

    static AccountCopyStore disk(Path root) {
        return disk(root, Clock.systemUTC());
    }

    static AccountCopyStore disk(Path root, Clock clock) {
        return new AccountCopyStore(
                root,
                new AccountCopyStore.Limits(
                        128,
                        16L * 1024 * 1024,
                        512L * 1024 * 1024,
                        Duration.ofDays(7),
                        new RetainedBaseline.Limits(64L * 1024 * 1024, 256L * 1024 * 1024, 100_000)),
                clock);
    }
}
