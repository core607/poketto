package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryTextChange;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Separate JVMs exercise the production journal on XFS; save and archive references are synthetic. */
record AccountCopyStoreNativeProbe(Path root) {
    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID WORKSPACE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID COPY = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID EXECUTION = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final AccountCopyRecord.Owner OWNER = new AccountCopyRecord.Owner(ACCOUNT, WORKSPACE, true);
    private static final Instant START = Instant.parse("2026-09-14T00:00:00Z");

    void produce() throws Exception {
        AccountCopyStore store = store(0);
        var state = new SelectedFileSaves.State("a".repeat(40));
        state.uncertain = true;
        state.pending = new RepositoryPatch(
                Optional.of(state.baseCommit),
                List.of(new RepositoryTextChange("private/草稿.md", true, Optional.empty(), Optional.of("等待核对的写入\n"))));
        var record = new AccountCopyRecord(
                1,
                OWNER,
                COPY,
                0,
                store.nextExpiry(),
                new AccountCopyRecord.Writer(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                AccountCopyRecord.Phase.RUNNING,
                EXECUTION,
                null,
                state.snapshot(),
                null,
                new AccountCopyRecord.Original("b".repeat(64), 128, 1));
        try (var lease = store.acquire(OWNER)) {
            assertThat(lease.record()).isEmpty();
            lease.write(record);
            assertThatThrownBy(() -> store.acquire(OWNER))
                    .isInstanceOfSatisfying(
                            RetainedCopyException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(RetainedCopyException.Reason.BUSY));
        }
    }

    void consume() throws Exception {
        AccountCopyStore store = store(2);
        try (var lease = store.acquire(OWNER)) {
            AccountCopyRecord recovered = lease.record().orElseThrow();
            assertThat(recovered.copyId()).isEqualTo(COPY);
            assertThat(recovered.executionId()).isEqualTo(EXECUTION);
            assertThat(recovered.phase()).isEqualTo(AccountCopyRecord.Phase.RUNNING);
            assertThat(recovered.state().originalCommit()).isEqualTo("a".repeat(40));
            assertThat(recovered.state().pending().changes().getFirst().content())
                    .contains("等待核对的写入\n");
            assertThat(store.expired(recovered)).isFalse();
            var continued = new AccountCopyRecord(
                    1,
                    OWNER,
                    COPY,
                    1,
                    store.nextExpiry(),
                    recovered.writer(),
                    AccountCopyRecord.Phase.READY,
                    null,
                    EXECUTION,
                    recovered.state(),
                    null,
                    recovered.original());
            lease.write(continued);
            assertThat(continued.original()).isEqualTo(recovered.original());
            assertThat(continued.expiresAt()).isGreaterThan(recovered.expiresAt());
            assertThatThrownBy(() -> lease.write(recovered)).isInstanceOf(IllegalArgumentException.class);
        }
        try (var foreign = store.acquire(new AccountCopyRecord.Owner(UUID.randomUUID(), WORKSPACE, true));
                var restricted = store.acquire(new AccountCopyRecord.Owner(ACCOUNT, WORKSPACE, false))) {
            assertThat(foreign.record()).isEmpty();
            assertThat(restricted.record()).isEmpty();
        }
        try (var lease = store(8).acquire(OWNER)) {
            var renewed = lease.record().orElseThrow();
            assertThat(store(8).expired(renewed)).isFalse();
            assertThat(store(10).expired(renewed)).isTrue();
            assertThat(renewed.lastInterruptedCommand()).isEqualTo(EXECUTION);
            lease.remove(COPY);
        }
        try (var removed = store.acquire(OWNER)) {
            assertThat(removed.record()).isEmpty();
        }
    }

    private AccountCopyStore store(int daysLater) {
        return new AccountCopyStore(
                root,
                new AccountCopyStore.Limits(4, 65536, 512L * 1024 * 1024, Duration.ofDays(7)),
                Clock.fixed(START.plus(Duration.ofDays(daysLater)), ZoneOffset.UTC));
    }
}
