package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The store's journal transition rules decide which record replacements survive on disk. */
class AccountCopyTransitionTests {
    private static final String FIRST = "1".repeat(40);
    private static final String SECOND = "2".repeat(40);
    private final AccountCopyRecord.Owner owner =
            new AccountCopyRecord.Owner(UUID.randomUUID(), UUID.randomUUID(), false);
    private final UUID copy = UUID.randomUUID();
    private final long expiry = System.currentTimeMillis() + 60_000;

    @Test
    void onlyARefreshingPublicCopyIsRepinnedToANewProjectionInOneWrite() {
        var refreshing = record(0, AccountCopyRecord.Phase.REFRESHING, FIRST);
        assertThatCode(() -> AccountCopyTransitions.requireTransition(
                        owner, refreshing, record(1, AccountCopyRecord.Phase.REFRESHING, SECOND), expiry))
                .doesNotThrowAnyException();

        var ready = record(0, AccountCopyRecord.Phase.READY, FIRST);
        for (var next : new AccountCopyRecord[] {
            record(1, AccountCopyRecord.Phase.READY, SECOND), record(1, AccountCopyRecord.Phase.REFRESHING, SECOND)
        }) {
            assertThatThrownBy(() -> AccountCopyTransitions.requireTransition(owner, ready, next, expiry))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pinned");
        }
        assertThatThrownBy(() -> AccountCopyTransitions.requireTransition(
                        owner, refreshing, record(1, AccountCopyRecord.Phase.READY, SECOND), expiry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pinned");
        var discarding = record(0, AccountCopyRecord.Phase.DISCARDING, FIRST);
        assertThatThrownBy(() -> AccountCopyTransitions.requireTransition(
                        owner, discarding, record(1, AccountCopyRecord.Phase.REFRESHING, SECOND), expiry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("executable again");
    }

    private AccountCopyRecord record(long revision, AccountCopyRecord.Phase phase, String commit) {
        var projection = new RepositorySnapshotExports.PublicExport(
                new WorkspaceId(owner.workspaceId()),
                new RepositorySnapshotExports.Export(UUID.randomUUID(), commit, "b".repeat(64), 128),
                "c".repeat(40),
                commit.substring(0, 1).repeat(64),
                Map.of(),
                Map.of());
        var writer = new AccountCopyRecord.Writer(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        return new AccountCopyRecord(
                owner,
                copy,
                revision,
                expiry,
                writer,
                phase,
                null,
                null,
                new SelectedFileSaves.State(commit).snapshot(),
                projection,
                null);
    }

    @Test
    void aCopyCountsAsUsedForTheWindowAfterItsLastCommandStarted() {
        var idle = Duration.ofDays(7);
        var window = Duration.ofMinutes(2);
        long started = 1_800_000_000_000L;
        long expiresAt = started + idle.toMillis();
        assertThat(AccountCopyStore.usedWithin(expiresAt, idle, started + 1, window))
                .isTrue();
        assertThat(AccountCopyStore.usedWithin(expiresAt, idle, started + window.toMillis() - 1, window))
                .isTrue();
        assertThat(AccountCopyStore.usedWithin(expiresAt, idle, started + window.toMillis(), window))
                .isFalse();
    }
}
