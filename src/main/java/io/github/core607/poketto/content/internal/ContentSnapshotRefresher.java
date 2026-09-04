package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.ContentRepositoryStore;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Re-validates each served workspace against remote {@code main} on a fixed delay, so a direct
 * owner push becomes visible without any request contacting the remote. A failed refresh leaves
 * the snapshot in service untouched and is logged once per outage.
 */
final class ContentSnapshotRefresher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ContentSnapshotRefresher.class);

    private final ContentRepositoryStore store;
    private final Supplier<List<WorkspaceId>> servedWorkspaces;
    private final Duration interval;
    private final Set<WorkspaceId> failing = ConcurrentHashMap.newKeySet();
    private volatile boolean listingFailing;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "content-snapshot-refresh");
        thread.setDaemon(true);
        return thread;
    });

    ContentSnapshotRefresher(
            ContentRepositoryStore store, Supplier<List<WorkspaceId>> servedWorkspaces, Duration interval) {
        this.store = Objects.requireNonNull(store, "content repository store must not be null");
        this.servedWorkspaces = Objects.requireNonNull(servedWorkspaces, "served workspaces must not be null");
        this.interval = Objects.requireNonNull(interval, "refresh interval must not be null");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("refresh interval must be positive");
        }
    }

    void start() {
        executor.scheduleWithFixedDelay(
                this::refreshAll, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void refreshAll() {
        final List<WorkspaceId> workspaces;
        try {
            workspaces = servedWorkspaces.get();
        } catch (RuntimeException exception) {
            if (!listingFailing) {
                listingFailing = true;
                log.warn("content snapshot refresh cannot list served workspaces", exception);
            }
            return;
        }
        if (listingFailing) {
            listingFailing = false;
            log.info("content snapshot refresh lists served workspaces again");
        }
        for (WorkspaceId workspaceId : workspaces) {
            refresh(workspaceId);
        }
    }

    private void refresh(WorkspaceId workspaceId) {
        try {
            store.refresh(workspaceId);
            if (failing.remove(workspaceId)) {
                log.info("workspace {} content snapshot refresh recovered", workspaceId);
            }
        } catch (ContentRepositoryException exception) {
            if (failing.add(workspaceId)) {
                log.warn(
                        "workspace {} keeps serving its last validated snapshot: {}",
                        workspaceId,
                        exception.getMessage());
            } else {
                log.debug("workspace {} content snapshot refresh still failing", workspaceId);
            }
        } catch (RuntimeException exception) {
            log.error("workspace {} content snapshot refresh failed unexpectedly", workspaceId, exception);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        // Interrupting a transport does not prove it stopped using the cache. Do not hand the
        // workspace back to the owner until the running refresh has released its resources.
        executor.close();
    }
}
