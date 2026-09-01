package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/** Shared per-workspace locks for operations that may advance authoritative repository refs. */
final class ContentRepositoryLocks {

    private final ConcurrentMap<WorkspaceId, Lock> locks = new ConcurrentHashMap<>();

    Lock forWorkspace(WorkspaceId workspaceId) {
        return locks.computeIfAbsent(workspaceId, ignored -> new ReentrantLock());
    }
}
