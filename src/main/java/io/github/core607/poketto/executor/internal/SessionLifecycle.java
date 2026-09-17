package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.SessionWorker.hash;
import static io.github.core607.poketto.executor.internal.SessionWorker.requireLive;
import static io.github.core607.poketto.executor.internal.SessionWorker.requireOk;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.PortableContentExports;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/** Owns account-copy admission, command execution and lease containment; the bridge handles CLI operations. */
/**
 * The life of one worker lease: opening a copy from an exported snapshot, attaching to a retained
 * one, refreshing the lease when a new principal arrives, renewing every live lease on a heartbeat,
 * and stopping a session so its worker capacity is released exactly once.
 *
 * <p>Session state changes happen under the session monitor and worker requests outside it, as
 * before. Closing runs on the control pool so a command thread never waits on a worker CLOSE it did
 * not ask for; a close that cannot be confirmed keeps its admission until a later pass settles it.
 */
final class SessionLifecycle {
    private static final Logger log = LoggerFactory.getLogger(SessionLifecycle.class);

    private final SessionRegistry registry;
    private final SessionWorker io;
    private final WorkerClient worker;
    private final RepositorySnapshotExports exports;
    private final PortableContentExports packages;
    private final SelectedFileSaves saves;
    private final AccountCopyStore accounts;
    private final Duration openTimeout;
    private final Duration closeTimeout;
    private final ThreadPoolExecutor controls = new ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128),
            Thread.ofPlatform().daemon().name("poketto-worker-control-", 0).factory());
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("poketto-worker-heartbeat").factory());

    SessionLifecycle(
            SessionRegistry registry,
            SessionWorker io,
            WorkerClient worker,
            RepositorySnapshotExports exports,
            PortableContentExports packages,
            SelectedFileSaves saves,
            AccountCopyStore accounts,
            Duration openTimeout,
            Duration closeTimeout) {
        this.registry = registry;
        this.io = io;
        this.worker = worker;
        this.exports = exports;
        this.packages = packages;
        this.saves = saves;
        this.accounts = accounts;
        this.openTimeout = openTimeout;
        this.closeTimeout = closeTimeout;
        heartbeat.scheduleWithFixedDelay(this::renewDue, 250, 250, TimeUnit.MILLISECONDS);
    }

    void initializeCopy(ExecutionSession session, Optional<String> requested, AccountCommand held) {
        if (!session.fullRead && requested.isPresent()) {
            throw new IllegalArgumentException("Public execution starts from current publication; omit commit");
        }
        session.hello = worker.hello();
        requireLive(session);
        RepositorySnapshotExports.Export export;
        if (session.fullRead) {
            export = exports.create(session.principal, session.key.workspace(), requested);
        } else {
            session.publicExport = exports.createPublic(session.principal, session.key.workspace());
            export = session.publicExport.export();
        }
        session.commit = export.commit();
        session.saveState = new SelectedFileSaves.State(session.commit);
        try {
            held.initialize(
                    session.copyId,
                    writer(session),
                    session.saveState.snapshot(),
                    session.publicExport,
                    sink -> saves.visitOriginal(
                            session.principal,
                            session.key.workspace(),
                            session.commit,
                            accounts.traversalLimits(),
                            sink));
            session.accountRecord = held.record();
            requireLive(session);
            io.authorize(session);
            synchronized (session) {
                requireLive(session);
                session.openAttempted = true;
                session.nextRenew = System.nanoTime()
                        + Duration.ofSeconds(session.hello.renewAfterSeconds()).toNanos();
            }
            long deadline = System.nanoTime() + openTimeout.toNanos();
            JsonNode response = io.request(
                    session,
                    "OPEN",
                    new WorkerRequests.Open(
                            session.copyId,
                            session.fullRead ? "full" : "public",
                            export.exportId(),
                            export.bundleSha256(),
                            export.bundleBytes(),
                            export.commit()),
                    openTimeout);
            awaitInitialized(session, response, deadline);
        } catch (RuntimeException exception) {
            try {
                stopAndAwait(session, "cancelled");
            } catch (RuntimeException closeFailure) {
                log.warn("Failed opening worker lease remains unconfirmed; renewal stopped", closeFailure);
            }
            throw exception;
        } finally {
            exports.release(export.exportId());
        }
    }

    /** Only OPEN/ATTACH replies reach this boundary; no user command has been submitted. */
    private static void requireInitializationAccepted(JsonNode response, boolean existingCopy) {
        if (!response.path("ok").isBoolean() || response.path("ok").booleanValue()) {
            return;
        }
        ExecutionAdmissionException.Reason reason =
                switch (response.path("code").asString("")) {
                    case "SESSION_CAPACITY", "REQUEST_CAPACITY" -> ExecutionAdmissionException.Reason.CAPACITY;
                    case "SESSION_BUSY", "COPY_BUSY", "SESSION_EXISTS" -> ExecutionAdmissionException.Reason.BUSY;
                    default -> null;
                };
        if (reason != null) {
            throw new ExecutionAdmissionException(reason, existingCopy);
        }
    }

    private void awaitInitialized(ExecutionSession session, JsonNode response, long deadline) {
        while (true) {
            requireInitializationAccepted(response, session.attaching);
            requireOk(response, session);
            requireLive(session);
            String state = response.path("state").asString("");
            if (state.equals("READY")) {
                session.gitCommit = WorkerResponses.read(response, WorkerResponses.GitBaseline.class)
                        .gitCommit();
                session.ready = true;
                return;
            }
            if (!state.equals("INITIALIZING") || System.nanoTime() >= deadline) {
                throw new WorkerUnavailableException();
            }
            pause();
            io.authorize(session);
            response = io.request(session, "RENEW", new WorkerRequests.Renew(), Duration.ofSeconds(3));
        }
    }

    static ExecutionSession.Key keyFor(AccountCopyRecord.Owner owner) {
        return new ExecutionSession.Key(
                owner.accountId(), new WorkspaceId(owner.workspaceId()), hash(owner.fullRead() ? "full" : "public"));
    }

    ExecutionSession refreshLease(ExecutionSession previous, AuthPrincipal principal) {
        if (!previous.stopping.get() && previous.principal.subjectId().equals(principal.subjectId())) {
            previous.principal = principal;
            return previous;
        }
        WorkerClient.Hello hello = worker.hello();
        if (previous.hello != null && !previous.hello.workerBootId().equals(hello.workerBootId())) {
            previous.stopping.set(true);
            registry.releaseCapacity(previous);
            previous.stopped.complete(null);
        } else {
            stopAndAwait(previous, "session_closed");
        }
        if (previous.commit == null || previous.saveState == null) {
            throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.RECOVERY_REQUIRED, false);
        }
        var current =
                new ExecutionSession(previous.key, principal, previous.fullRead, previous.copyId, UUID.randomUUID());
        current.busy.set(true);
        current.attaching = true;
        current.hello = hello;
        current.commit = previous.commit;
        current.saveState = previous.saveState;
        current.publicExport = previous.publicExport;
        current.accountRecord = previous.accountRecord;
        if (!registry.replace(previous, current)) {
            throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.UNAVAILABLE, true);
        }
        previous.busy.set(false);
        return current;
    }

    void attach(ExecutionSession session) {
        io.authorize(session);
        synchronized (session) {
            requireLive(session);
            session.openAttempted = true;
            session.nextRenew = System.nanoTime()
                    + Duration.ofSeconds(session.hello.renewAfterSeconds()).toNanos();
        }
        JsonNode response = io.request(
                session,
                "ATTACH",
                new WorkerRequests.DiskCopy(session.copyId, session.fullRead ? "full" : "public", session.commit),
                openTimeout);
        requireInitializationAccepted(response, true);
        requireOk(response, session);
        if (!response.path("state").asString("").equals("READY")) {
            throw new WorkerUnavailableException();
        }
        session.gitCommit = WorkerResponses.read(response, WorkerResponses.GitBaseline.class)
                .gitCommit();
        session.ready = true;
    }

    private void renewDue() {
        List<ExecutionSession> current = registry.all();
        for (ExecutionSession session : current) {
            if (session.stopping.get()) {
                reconcileClose(session);
                continue;
            }
            AccountCopyRecord record = session.accountRecord;
            if (record != null && accounts.expired(record) && !session.busy.get()) {
                stop(session, "session_closed");
                continue;
            }
            if (!session.openAttempted
                    || session.stopping.get()
                    || System.nanoTime() < session.nextRenew
                    || !session.renewing.compareAndSet(false, true)) {
                continue;
            }
            try {
                controls.execute(() -> {
                    try {
                        if (session.stopping.get()) {
                            return;
                        }
                        io.authorize(session);
                        JsonNode response =
                                io.request(session, "RENEW", new WorkerRequests.Renew(), Duration.ofSeconds(3));
                        requireOk(response, session);
                        String state = response.path("state").asString("");
                        if (!List.of("INITIALIZING", "READY", "RUNNING").contains(state)) {
                            throw new WorkerUnavailableException();
                        }
                        session.nextRenew = System.nanoTime()
                                + Duration.ofSeconds(session.hello.renewAfterSeconds())
                                        .toNanos();
                    } catch (RuntimeException exception) {
                        log.warn("Worker lease renewal failed", exception);
                        stop(session, "cancelled");
                    } finally {
                        session.renewing.set(false);
                    }
                });
            } catch (RuntimeException exception) {
                session.renewing.set(false);
                stop(session, "cancelled");
            }
        }
    }

    private void reconcileClose(ExecutionSession session) {
        if (!session.openAttempted
                || session.capacityReleased
                || !session.stopped.isCompletedExceptionally()
                || System.nanoTime() < session.nextRenew
                || !session.renewing.compareAndSet(false, true)) {
            return;
        }
        Runnable deferred = () -> {
            session.nextRenew = System.nanoTime()
                    + Duration.ofSeconds(session.hello.renewAfterSeconds()).toNanos();
            session.renewing.set(false);
        };
        try {
            controls.execute(() -> {
                try {
                    if (session.capacityReleased || !session.stopping.get()) {
                        return;
                    }
                    // Closing remains allowed after revocation; no OPEN, EXEC or renewal is replayed.
                    closeWorker(session, session.closeReason);
                    registry.releaseCapacity(session);
                } catch (RuntimeException exception) {
                    log.debug("Worker close remains unconfirmed; admission is retained", exception);
                } finally {
                    deferred.run();
                }
            });
        } catch (RuntimeException exception) {
            deferred.run();
        }
    }

    CompletableFuture<Void> stop(ExecutionSession session, String reason) {
        synchronized (session) {
            if (session.capacityReleased) {
                return CompletableFuture.completedFuture(null);
            }
            if (!session.stopping.compareAndSet(false, true)) {
                return session.stopped;
            }
            session.closeReason = reason;
            packages.closeClient(session.principal, session.key.workspace(), session.key.scopeHash());
            if (!session.openAttempted) {
                registry.releaseCapacity(session);
                session.stopped.complete(null);
                return session.stopped;
            }
        }
        try {
            controls.execute(() -> {
                try {
                    closeWorker(session, reason);
                    registry.releaseCapacity(session);
                    session.stopped.complete(null);
                } catch (RuntimeException exception) {
                    session.stopped.completeExceptionally(exception);
                }
            });
        } catch (RuntimeException exception) {
            session.stopped.completeExceptionally(new WorkerUnavailableException());
        }
        return session.stopped;
    }

    void stopAndAwait(ExecutionSession session, String reason) {
        try {
            if (session.capacityReleased) {
                return;
            }
            if (session.stopped.isCompletedExceptionally()) {
                // A new caller retries only the idempotent CLOSE, never the failed command.
                closeWorker(session, session.closeReason);
                registry.releaseCapacity(session);
                return;
            }
            stop(session, reason).get(closeTimeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new WorkerUnavailableException(exception);
        }
    }

    private void closeWorker(ExecutionSession session, String reason) {
        long deadline = System.nanoTime() + closeTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode response = session.auxiliary
                    ? worker.closeRetainedLease(
                            session.hello, session.identity(), session.retainedAppBoot, reason, Duration.ofSeconds(3))
                    : worker.request(
                            session.hello,
                            session.identity(),
                            "CLOSE",
                            new WorkerRequests.Close(reason),
                            Duration.ofSeconds(3));
            requireOk(response, session);
            String state = response.path("state").asString("");
            if (state.equals("CLOSED")) {
                return;
            }
            if (!state.equals("CLOSING")) {
                throw new WorkerUnavailableException();
            }
            pause();
        }
        throw new WorkerUnavailableException();
    }

    void fenceAccount(
            AuthPrincipal principal, AccountCopyRecord record, ExecutionSession.Key key, WorkerClient.Hello hello) {
        ExecutionSession control = registry.controlSession(key, record.writer().leaseId(), () -> {
            var created = new ExecutionSession(
                    key,
                    principal,
                    record.owner().fullRead(),
                    record.copyId(),
                    record.writer().leaseId());
            created.auxiliary = true;
            created.retainedAppBoot = record.writer().appBootId();
            created.priorPrincipal = record.writer().principalId();
            created.commit = record.state().originalCommit();
            created.publicExport = record.publicExport();
            created.hello = hello;
            created.openAttempted = true;
            return created;
        });
        if (record.writer().workerBootId().equals(hello.workerBootId())) {
            stopAndAwait(control, "session_closed");
        } else {
            control.stopping.set(true);
            registry.releaseCapacity(control);
            control.stopped.complete(null);
        }
        registry.remove(key, control);
    }

    /** The writer identity a session records in the account journal. */
    AccountCopyRecord.Writer writer(ExecutionSession session) {
        return new AccountCopyRecord.Writer(
                session.principal.subjectId(),
                session.hello.workerBootId(),
                worker.applicationBootId(),
                session.leaseId);
    }

    /** Waits between CLOSE attempts while the worker reports CLOSING. */
    static void pause() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new WorkerUnavailableException();
        }
    }

    /** Stops the heartbeat and the control pool; the caller stops the sessions themselves first. */
    void shutdown() {
        heartbeat.shutdownNow();
        controls.shutdownNow();
    }
}
