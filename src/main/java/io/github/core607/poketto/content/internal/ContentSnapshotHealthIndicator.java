package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryStore;
import io.github.core607.poketto.content.ContentSnapshot;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Readiness of the served content: {@code DOWN} without a validated snapshot for the default
 * workspace, {@code OUT_OF_SERVICE} when the snapshot has not been re-validated within the stale
 * bound, {@code UP} otherwise. It never contacts the remote.
 */
final class ContentSnapshotHealthIndicator implements HealthIndicator {

    private final ContentRepositoryStore store;
    private final Supplier<WorkspaceId> defaultWorkspace;
    private final Duration staleAfter;
    private final Clock clock;

    ContentSnapshotHealthIndicator(
            ContentRepositoryStore store, Supplier<WorkspaceId> defaultWorkspace, Duration staleAfter, Clock clock) {
        this.store = Objects.requireNonNull(store, "content repository store must not be null");
        this.defaultWorkspace = Objects.requireNonNull(defaultWorkspace, "default workspace must not be null");
        this.staleAfter = Objects.requireNonNull(staleAfter, "stale bound must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Health health() {
        Optional<ContentSnapshot> snapshot = store.snapshot(defaultWorkspace.get());
        if (snapshot.isEmpty()) {
            return Health.down()
                    .withDetail("reason", "no validated content snapshot")
                    .build();
        }
        Duration age = Duration.between(snapshot.get().validatedAt(), clock.instant());
        Health.Builder health = age.compareTo(staleAfter) >= 0 ? Health.outOfService() : Health.up();
        return health.withDetail("commit", snapshot.get().commitId().orElse("unborn"))
                .withDetail("validatedAt", snapshot.get().validatedAt().toString())
                .withDetail("documents", snapshot.get().documents().size())
                .build();
    }
}
