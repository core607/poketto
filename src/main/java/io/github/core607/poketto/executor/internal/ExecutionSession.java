package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An account copy's current execution lease. The busy claim serializes commands and principal
 * refreshes; changing grants fences the old lease before attaching the same files. Worker
 * requests remain bound to that lease's grant, while every host operation rechecks current
 * authorization. Closing a transport does not release this binding.
 *
 * <p>Command, renewal and revocation threads share the lifecycle latches. Capacity is released
 * only after confirmed containment, never merely because an RPC or a close request failed.
 */
final class ExecutionSession {
    record Key(UUID account, WorkspaceId workspace, String scopeHash) {}

    final UUID copyId;
    final ExecutionSession.Key key;
    volatile AuthPrincipal principal;
    final boolean fullRead;
    volatile RepositorySnapshotExports.PublicExport publicExport;
    final UUID leaseId;
    final AtomicBoolean busy = new AtomicBoolean();
    final AtomicBoolean stopping = new AtomicBoolean();
    final AtomicBoolean renewing = new AtomicBoolean();
    final CompletableFuture<Void> stopped = new CompletableFuture<>();
    final CompletableFuture<Void> contained = new CompletableFuture<>();
    volatile WorkerClient.Hello hello;
    volatile String commit;
    String gitCommit;
    SelectedFileSaves.State saveState;
    volatile AccountCopyRecord accountRecord;
    volatile boolean openAttempted;
    volatile boolean ready;
    boolean attaching;
    volatile boolean capacityReleased;
    volatile boolean detached;
    /**
     * Fixed at construction, and true only for the dedicated lease that inspects a public copy whose
     * publication changed. That lease runs no caller command and returns no content, so it needs
     * execution permission, not a current projection. No other lease can acquire the flag, and the
     * refresh closes the inspection lease on every path instead of returning it to the caller.
     */
    final boolean projectionCheck;

    boolean auxiliary;
    UUID retainedAppBoot;
    UUID priorPrincipal;
    String closeReason;
    volatile long nextRenew;

    ExecutionSession(ExecutionSession.Key key, AuthPrincipal principal, boolean fullRead, UUID copyId, UUID leaseId) {
        this(key, principal, fullRead, copyId, leaseId, false);
    }

    ExecutionSession(
            ExecutionSession.Key key,
            AuthPrincipal principal,
            boolean fullRead,
            UUID copyId,
            UUID leaseId,
            boolean projectionCheck) {
        this.key = key;
        this.principal = principal;
        this.fullRead = fullRead;
        this.copyId = copyId;
        this.leaseId = leaseId;
        this.projectionCheck = projectionCheck;
    }

    WorkerClient.Identity identity() {
        return new WorkerClient.Identity(
                priorPrincipal == null ? principal.subjectId() : priorPrincipal,
                principal.accountId(),
                key.workspace().value(),
                key.scopeHash(),
                leaseId);
    }
}
