package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.core607.poketto.content.ContentRepositoryStore;
import io.github.core607.poketto.content.ContentSnapshot;
import io.github.core607.poketto.content.StoredDocument;
import io.github.core607.poketto.workspace.Workspace;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class ContentSnapshotHealthIndicatorTests {

    private static final Workspace DEFAULT = new Workspace(WorkspaceId.random(), "Default workspace");
    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

    private final SnapshotStore store = new SnapshotStore();
    private final ContentSnapshotHealthIndicator indicator = new ContentSnapshotHealthIndicator(
            store, DEFAULT::id, Duration.ofHours(1), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void isDownWithoutAValidatedSnapshot() {
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "no validated content snapshot");
    }

    @Test
    void isUpWhileTheSnapshotIsWithinTheStaleBound() {
        store.snapshot = new ContentSnapshot(
                DEFAULT.id(), Optional.of("a".repeat(40)), List.of(), NOW.minus(Duration.ofMinutes(59)));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("commit", "a".repeat(40)).containsEntry("documents", 0);
    }

    @Test
    void isOutOfServiceOnceTheSnapshotOutlivesTheStaleBound() {
        store.snapshot =
                new ContentSnapshot(DEFAULT.id(), Optional.empty(), List.of(), NOW.minus(Duration.ofMinutes(61)));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails()).containsEntry("commit", "unborn");
    }

    @Test
    void isOutOfServiceAtTheExactStaleDeadline() {
        store.snapshot = new ContentSnapshot(DEFAULT.id(), Optional.empty(), List.of(), NOW.minus(Duration.ofHours(1)));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }

    private static final class SnapshotStore implements ContentRepositoryStore {

        private ContentSnapshot snapshot;

        @Override
        public void ensureReady(WorkspaceId workspaceId) {}

        @Override
        public ContentSnapshot refresh(WorkspaceId workspaceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ContentSnapshot> snapshot(WorkspaceId workspaceId) {
            return DEFAULT.id().equals(workspaceId) ? Optional.ofNullable(snapshot) : Optional.empty();
        }

        @Override
        public List<StoredDocument> scan(WorkspaceId workspaceId) {
            throw new UnsupportedOperationException();
        }
    }
}
