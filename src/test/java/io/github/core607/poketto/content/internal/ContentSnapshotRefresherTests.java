package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ContentSnapshotRefresherTests {

    @Test
    void closeWaitsForAnInterruptedRefreshToFinishUsingTheCache() throws Exception {
        WorkspaceId workspace = WorkspaceId.random();
        ContentRepositoryStore store = mock(ContentRepositoryStore.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(store.refresh(workspace)).thenAnswer(invocation -> {
            started.countDown();
            boolean waiting = true;
            while (waiting) {
                try {
                    release.await();
                    waiting = false;
                } catch (InterruptedException ignored) {
                    // A transport can defer cancellation until its current operation completes.
                    interrupted.countDown();
                }
            }
            finished.countDown();
            return new ContentSnapshot(workspace, Optional.empty(), List.of(), Instant.EPOCH);
        });
        ContentSnapshotRefresher refresher =
                new ContentSnapshotRefresher(store, () -> List.of(workspace), Duration.ofMillis(1));
        try (var closer = Executors.newSingleThreadExecutor()) {
            refresher.start();
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                var closed = closer.submit(refresher::close);
                assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> closed.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                closed.get(5, TimeUnit.SECONDS);
                assertThat(finished.getCount()).isZero();
            } finally {
                release.countDown();
                refresher.close();
            }
        }
    }

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
