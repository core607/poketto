package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.workspace.WorkspaceId;
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
        assertThatCode(() -> AccountCopyStore.requireTransition(
                        owner, refreshing, record(1, AccountCopyRecord.Phase.REFRESHING, SECOND), expiry))
                .doesNotThrowAnyException();

        var ready = record(0, AccountCopyRecord.Phase.READY, FIRST);
        for (var next : new AccountCopyRecord[] {
            record(1, AccountCopyRecord.Phase.READY, SECOND), record(1, AccountCopyRecord.Phase.REFRESHING, SECOND)
        }) {
            assertThatThrownBy(() -> AccountCopyStore.requireTransition(owner, ready, next, expiry))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pinned");
        }
        assertThatThrownBy(() -> AccountCopyStore.requireTransition(
                        owner, refreshing, record(1, AccountCopyRecord.Phase.READY, SECOND), expiry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pinned");
        var discarding = record(0, AccountCopyRecord.Phase.DISCARDING, FIRST);
        assertThatThrownBy(() -> AccountCopyStore.requireTransition(
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
                1,
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
}
