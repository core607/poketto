package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.function.Supplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

final class PublicSnapshotHealthIndicator implements HealthIndicator {
    private final PublicContentSnapshots snapshots;
    private final Supplier<WorkspaceId> workspace;

    PublicSnapshotHealthIndicator(PublicContentSnapshots snapshots, Supplier<WorkspaceId> workspace) {
        this.snapshots = snapshots;
        this.workspace = workspace;
    }

    @Override
    public Health health() {
        try {
            snapshots.current(workspace.get());
            return Health.up().build();
        } catch (ContentRepositoryException unavailable) {
            return Health.outOfService()
                    .withDetail("reason", "public content snapshot is unavailable")
                    .build();
        }
    }
}
