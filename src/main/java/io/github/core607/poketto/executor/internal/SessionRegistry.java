package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.mcp.RepositoryExecutor.NEW_COPY;

import io.github.core607.poketto.auth.AuthRevocation;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.RepositoryExecutor.CopyRequest;
import io.github.core607.poketto.mcp.SessionReplacedException;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * The executor's table of live leases: the session of each account/workspace/scope key, the leases
 * that are closing but still hold worker capacity, the admission bounds for sessions and concurrent
 * operations, and the counters behind the executor's metrics.
 *
 * <p>Every read-then-write of the table happens under this object's monitor, so a reservation, a
 * lease refresh and a capacity release cannot interleave. Callers construct sessions outside the
 * monitor and hand them in, because construction touches no shared state; worker requests and disk
 * work stay outside it for the same reason they always did.
 */
final class SessionRegistry {
    /** Concurrent operations across all sessions; the executor's gauge reports what is in use. */
    private static final int MAX_OPERATIONS = 4;

    private final int maxSessions;
    private final Map<ExecutionSession.Key, ExecutionSession> sessions = new LinkedHashMap<>();
    private final Map<UUID, ExecutionSession> closingLeases = new LinkedHashMap<>();
    private final Semaphore executions = new Semaphore(MAX_OPERATIONS, true);
    private final LongAdder createdCopies = new LongAdder();
    private final LongAdder releasedCopies = new LongAdder();
    private final Map<String, LongAdder> rejected = Map.of(
            "copy_mismatch", new LongAdder(),
            "session_limit", new LongAdder(),
            "operation_limit", new LongAdder(),
            "session_busy", new LongAdder());
    private volatile boolean closed;

    SessionRegistry(int maxSessions) {
        this.maxSessions = maxSessions;
    }

    boolean isClosed() {
        return closed;
    }

    /** The current session of this key, refusing outright once the executor is closed. */
    synchronized ExecutionSession openSession(ExecutionSession.Key key) {
        if (closed) {
            throw new WorkerUnavailableException();
        }
        return sessions.get(key);
    }

    synchronized ExecutionSession session(ExecutionSession.Key key) {
        return sessions.get(key);
    }

    /**
     * Claims one idle session's busy flag so its caller can stop it and free capacity. Null when
     * capacity is available or every session is in use.
     */
    synchronized ExecutionSession idleCandidate() {
        if (activeSessions() < maxSessions) {
            return null;
        }
        for (ExecutionSession candidate : sessions.values()) {
            if (!candidate.capacityReleased && candidate.busy.compareAndSet(false, true)) {
                return candidate;
            }
        }
        return null;
    }

    /** Admits a new session under the capacity bounds and registers it busy. */
    synchronized ExecutionSession reserve(ExecutionSession.Key key, Supplier<ExecutionSession> factory) {
        if (closed || activeSessions() >= maxSessions || sessions.size() >= 1024) {
            throw rejected("session_limit");
        }
        ExecutionSession current = factory.get();
        current.busy.set(true);
        sessions.put(key, current);
        createdCopies.increment();
        return current;
    }

    /**
     * The session or closing lease that owns this record's lease, creating and tracking a control
     * session when neither holds it. The lookup and the insert share one critical section, so two
     * callers cannot both create a control session for the same lease.
     */
    synchronized ExecutionSession controlSession(
            ExecutionSession.Key key, UUID leaseId, Supplier<ExecutionSession> factory) {
        ExecutionSession control = sessions.get(key);
        if (control == null || !control.leaseId.equals(leaseId)) {
            control = closingLeases.get(leaseId);
        }
        if (control == null) {
            control = factory.get();
            closingLeases.put(control.leaseId, control);
        }
        return control;
    }

    synchronized void remove(ExecutionSession.Key key, ExecutionSession session) {
        sessions.remove(key, session);
    }

    /** Installs the refreshed lease in place of its predecessor; false when the table moved on. */
    synchronized boolean replace(ExecutionSession previous, ExecutionSession current) {
        return !closed && sessions.replace(previous.key, previous, current);
    }

    /** Every session and closing lease, for the heartbeat that renews and reconciles them. */
    synchronized List<ExecutionSession> all() {
        var current = new ArrayList<>(sessions.values());
        current.addAll(closingLeases.values());
        return current;
    }

    synchronized List<ExecutionSession> affectedBy(AuthRevocation event) {
        return sessions.values().stream()
                .filter(session -> session.key.workspace().equals(event.workspaceId())
                        && (event.apiKeyIds().contains(session.principal.subjectId())
                                || event.accountIds().contains(session.principal.accountId())))
                .toList();
    }

    /**
     * Releases confirmed worker capacity while preserving the account binding. A later command
     * can attach the same disk files under a new lease; explicit disposal removes the binding.
     */
    synchronized void releaseCapacity(ExecutionSession session) {
        if (!session.capacityReleased && !session.auxiliary) {
            releasedCopies.increment();
        }
        session.capacityReleased = true;
        session.contained.complete(null);
        closingLeases.remove(session.leaseId, session);
        if (session.detached) {
            sessions.remove(session.key, session);
        }
    }

    /** Closes the table permanently and hands back everything that still needs stopping. */
    synchronized List<ExecutionSession> drain() {
        closed = true;
        var remaining = new ArrayList<>(sessions.values());
        remaining.addAll(closingLeases.values());
        sessions.clear();
        closingLeases.clear();
        return remaining;
    }

    boolean tryAcquireOperation() {
        return executions.tryAcquire();
    }

    boolean tryAcquireOperation(Duration timeout) throws InterruptedException {
        return executions.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    void releaseOperation() {
        executions.release();
    }

    /** A named copy must be the one this account holds; a replacement is reported, never silently used. */
    void requireCopyMatch(AccountCopyRecord record, CopyRequest expected) {
        if (!NEW_COPY.equals(expected.id())
                && (record == null || !record.copyId().toString().equals(expected.id()))) {
            rejected.get("copy_mismatch").increment();
            throw new SessionReplacedException(
                    record == null
                            ? SessionReplacedException.Reason.MISSING_COPY
                            : SessionReplacedException.Reason.DIFFERENT_COPY,
                    record == null
                            ? Optional.empty()
                            : Optional.of(record.copyId().toString()),
                    record == null);
        }
    }

    ExecutionAdmissionException rejected(String reason) {
        rejected.get(reason).increment();
        return new ExecutionAdmissionException(
                reason.equals("session_limit")
                        ? ExecutionAdmissionException.Reason.CAPACITY
                        : ExecutionAdmissionException.Reason.BUSY,
                false);
    }

    void bindMetrics(MeterRegistry registry) {
        registry.gauge("poketto.executor.sessions.active", this, SessionRegistry::activeSessions);
        registry.gauge(
                "poketto.executor.operations.active",
                this,
                value -> MAX_OPERATIONS - value.executions.availablePermits());
        FunctionCounter.builder("poketto.executor.sessions.created", createdCopies, LongAdder::doubleValue)
                .register(registry);
        FunctionCounter.builder("poketto.executor.sessions.released", releasedCopies, LongAdder::doubleValue)
                .register(registry);
        rejected.forEach((reason, counter) -> FunctionCounter.builder(
                        "poketto.executor.admission.rejected", counter, LongAdder::doubleValue)
                .tag("reason", reason)
                .register(registry));
    }

    private synchronized double activeSessions() {
        return sessions.values().stream()
                .filter(value -> !value.capacityReleased)
                .count();
    }
}
