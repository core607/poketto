package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.ContentRepositoryStore;
import io.github.core607.poketto.content.ContentSnapshot;
import io.github.core607.poketto.content.StoredDocument;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ContentSnapshotRefresherTests {

    @Test
    void refreshesEveryServedWorkspaceAndSurvivesAFailingOne() {
        WorkspaceId healthy = WorkspaceId.random();
        WorkspaceId failing = WorkspaceId.random();
        CountingStore store = new CountingStore(failing);
        try (ContentSnapshotRefresher refresher =
                new ContentSnapshotRefresher(store, () -> List.of(failing, healthy), Duration.ofSeconds(1))) {
            refresher.refreshAll();
            refresher.refreshAll();
        }

        assertThat(store.refreshes.get(healthy).get()).isEqualTo(2);
        assertThat(store.refreshes.get(failing).get()).isEqualTo(2);
    }

    @Test
    void aFailingWorkspaceListingRefreshesNothing() {
        CountingStore store = new CountingStore(WorkspaceId.random());
        try (ContentSnapshotRefresher refresher = new ContentSnapshotRefresher(
                store,
                () -> {
                    throw new IllegalStateException("catalog unavailable");
                },
                Duration.ofSeconds(1))) {
            refresher.refreshAll();
        }

        assertThat(store.refreshes).isEmpty();
    }

    private static final class CountingStore implements ContentRepositoryStore {

        private final Map<WorkspaceId, AtomicInteger> refreshes = new ConcurrentHashMap<>();
        private final WorkspaceId failing;

        private CountingStore(WorkspaceId failing) {
            this.failing = failing;
        }

        @Override
        public void ensureReady(WorkspaceId workspaceId) {}

        @Override
        public ContentSnapshot refresh(WorkspaceId workspaceId) {
            refreshes
                    .computeIfAbsent(workspaceId, ignored -> new AtomicInteger())
                    .incrementAndGet();
            if (workspaceId.equals(failing)) {
                throw new ContentRepositoryException("workspace " + workspaceId + " remote unreachable");
            }
            return new ContentSnapshot(workspaceId, Optional.empty(), List.of(), Instant.EPOCH);
        }

        @Override
        public Optional<ContentSnapshot> snapshot(WorkspaceId workspaceId) {
            return Optional.empty();
        }

        @Override
        public List<StoredDocument> scan(WorkspaceId workspaceId) {
            throw new UnsupportedOperationException();
        }
    }
}
