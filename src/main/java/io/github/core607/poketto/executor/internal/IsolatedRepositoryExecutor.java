package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthRevocation;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.McpSessionClosed;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import tools.jackson.databind.JsonNode;

/** Maintains trusted MCP execution leases; authority exports and worker directories have separate lifetimes. */
final class IsolatedRepositoryExecutor implements RepositoryExecutor, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(IsolatedRepositoryExecutor.class);
    private final AuthService auth;
    private final RepositorySnapshotExports exports;
    private final WorkerClient worker;
    private final int maxSessions;
    private final Duration openTimeout;
    private final Duration closeTimeout;
    private final Map<SessionKey, Session> sessions = new LinkedHashMap<>();
    private final Semaphore executions = new Semaphore(4);
    private final ThreadPoolExecutor controls = new ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128),
            Thread.ofPlatform().daemon().name("poketto-worker-control-", 0).factory());
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("poketto-worker-heartbeat").factory());
    private boolean closed;

    IsolatedRepositoryExecutor(
            AuthService auth,
            RepositorySnapshotExports exports,
            WorkerClient worker,
            int maxSessions,
            Duration openTimeout,
            Duration closeTimeout) {
        this.auth = auth;
        this.exports = exports;
        this.worker = worker;
        this.maxSessions = maxSessions;
        this.openTimeout = openTimeout;
        this.closeTimeout = closeTimeout;
        heartbeat.scheduleWithFixedDelay(this::renewDue, 250, 250, TimeUnit.MILLISECONDS);
    }

    @Override
    public ExecutionResult execute(
            AuthPrincipal principal,
            WorkspaceId workspace,
            String serverSessionId,
            Optional<String> requestedCommit,
            String command,
            Duration timeout,
            ExecutionCancellation cancellation) {
        authorize(principal, workspace);
        if (serverSessionId == null
                || serverSessionId.isBlank()
                || serverSessionId.length() > 128
                || command == null
                || command.isBlank()
                || command.length() > 16384
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(60)) > 0
                || requestedCommit
                        .filter(value -> !value.matches("[0-9a-f]{40}"))
                        .isPresent()) throw new IllegalArgumentException("Invalid bounded execution request");
        if (cancellation.isCancelled() || !executions.tryAcquire()) throw new WorkerUnavailableException();
        Session session = null;
        boolean ownsCommand = false;
        try {
            SessionKey key = new SessionKey(principal.subjectId(), workspace, hash(serverSessionId));
            recoverRestartedLeases(key);
            synchronized (this) {
                if (closed) throw new WorkerUnavailableException();
                session = sessions.get(key);
                if (session == null) {
                    if (sessions.size() >= 1024
                            || sessions.values().stream()
                                            .filter(value -> !value.capacityReleased)
                                            .count()
                                    >= maxSessions) throw new WorkerUnavailableException();
                    session = new Session(key, principal);
                    sessions.put(key, session);
                }
            }
            if (!session.busy.compareAndSet(false, true)) throw new WorkerUnavailableException();
            ownsCommand = true;
            Session selected = session;
            try (var registration = cancellation.onCancel(() -> stopAndAwait(selected, "cancelled"))) {
                requireLive(session);
                if (session.commit != null
                        && requestedCommit.isPresent()
                        && !session.commit.equals(requestedCommit.get()))
                    throw new IllegalArgumentException(
                            "Execution session remains pinned; use a new MCP session for another commit");
                if (!session.ready) open(session, requestedCommit);
                requireLive(session);
                authorize(principal, workspace);
                JsonNode response = requestLive(
                        session,
                        "EXEC",
                        Map.of(
                                "executionId",
                                UUID.randomUUID(),
                                "commit",
                                session.commit,
                                "command",
                                command,
                                "timeoutMillis",
                                timeout.toMillis()),
                        timeout.plusSeconds(5));
                requireOk(response, session);
                ExecutionResult result = result(response.path("result"), session.commit);
                String state = response.path("state").asString("");
                if (state.equals("CLOSING")
                        || state.equals("CLOSED")
                        || result.terminationReason() == TerminationReason.CANCELLED
                        || result.terminationReason() == TerminationReason.REVOKED
                        || result.terminationReason() == TerminationReason.SANDBOX_FAILURE)
                    stopAndAwait(session, "cancelled");
                return result;
            }
        } catch (RuntimeException exception) {
            if (session != null && ownsCommand && !(exception instanceof IllegalArgumentException)) {
                try {
                    stopAndAwait(session, "cancelled");
                } catch (RuntimeException closeFailure) {
                    log.warn("Worker termination not acknowledged; lease renewal has stopped");
                }
            }
            throw exception;
        } finally {
            if (session != null && ownsCommand) session.busy.set(false);
            executions.release();
        }
    }

    private void recoverRestartedLeases(SessionKey requested) {
        List<Session> candidates;
        synchronized (this) {
            if (closed
                    || sessions.containsKey(requested)
                    || sessions.values().stream()
                                    .filter(value -> !value.capacityReleased)
                                    .count()
                            < maxSessions) return;
            candidates = sessions.values().stream()
                    .filter(value -> !value.capacityReleased && value.openAttempted)
                    .toList();
        }
        if (candidates.isEmpty()) return;
        WorkerClient.Hello current = worker.hello();
        // The root worker serves HELLO only after exclusive startup cleanup has stopped all old units.
        // Only candidates captured before this probe can be retired by its boot identity.
        for (Session candidate : candidates) {
            synchronized (candidate) {
                if (!candidate.hello.workerBootId().equals(current.workerBootId())) {
                    candidate.stopping.set(true);
                    releaseCapacity(candidate);
                    candidate.stopped.complete(null);
                }
            }
        }
    }

    private void open(Session session, Optional<String> requested) {
        session.hello = worker.hello();
        requireLive(session);
        RepositorySnapshotExports.Export export = exports.create(session.principal, session.key.workspace(), requested);
        session.commit = export.commit();
        try {
            requireLive(session);
            authorize(session.principal, session.key.workspace());
            synchronized (session) {
                requireLive(session);
                session.openAttempted = true;
                session.nextRenew = System.nanoTime()
                        + Duration.ofSeconds(session.hello.renewAfterSeconds()).toNanos();
            }
            long deadline = System.nanoTime() + openTimeout.toNanos();
            JsonNode response = requestLive(
                    session,
                    "OPEN",
                    Map.of(
                            "exportId",
                            export.exportId(),
                            "bundleSha256",
                            export.bundleSha256(),
                            "bundleBytes",
                            export.bundleBytes(),
                            "commit",
                            export.commit()),
                    openTimeout);
            while (true) {
                requireOk(response, session);
                requireLive(session);
                String state = response.path("state").asString("");
                if (state.equals("READY")) {
                    session.ready = true;
                    return;
                }
                if (!state.equals("INITIALIZING") || System.nanoTime() >= deadline)
                    throw new WorkerUnavailableException();
                pause();
                authorize(session.principal, session.key.workspace());
                response = requestLive(session, "RENEW", Map.of(), Duration.ofSeconds(3));
            }
        } catch (RuntimeException exception) {
            try {
                stopAndAwait(session, "cancelled");
            } catch (RuntimeException closeFailure) {
                log.warn("Failed opening worker lease remains unconfirmed; renewal stopped");
            }
            throw exception;
        } finally {
            exports.release(export.exportId());
        }
    }

    private void authorize(AuthPrincipal principal, WorkspaceId workspace) {
        if (principal == null || principal.kind() != AuthPrincipal.Kind.API_KEY)
            throw new SecurityException("Execution requires an API key");
        auth.authorize(principal, workspace, Capability.READ_PRIVATE, Capability.EXECUTE_REPOSITORY);
    }

    private void renewDue() {
        List<Session> current;
        synchronized (this) {
            current = new ArrayList<>(sessions.values());
        }
        for (Session session : current) {
            if (!session.openAttempted
                    || session.stopping.get()
                    || System.nanoTime() < session.nextRenew
                    || !session.renewing.compareAndSet(false, true)) continue;
            try {
                controls.execute(() -> {
                    try {
                        if (session.stopping.get()) return;
                        authorize(session.principal, session.key.workspace());
                        JsonNode response = requestLive(session, "RENEW", Map.of(), Duration.ofSeconds(3));
                        requireOk(response, session);
                        String state = response.path("state").asString("");
                        if (!List.of("INITIALIZING", "READY", "RUNNING").contains(state))
                            throw new WorkerUnavailableException();
                        session.nextRenew = System.nanoTime()
                                + Duration.ofSeconds(session.hello.renewAfterSeconds())
                                        .toNanos();
                    } catch (RuntimeException exception) {
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

    private JsonNode requestLive(Session session, String operation, Map<String, ?> data, Duration timeout) {
        WorkerClient.PreparedRequest request;
        synchronized (session) {
            requireLive(session);
            request = worker.prepare(session.hello, session.identity(), operation, data);
        }
        return worker.send(request, timeout);
    }

    private CompletableFuture<Void> stop(Session session, String reason) {
        synchronized (session) {
            if (session.capacityReleased) return CompletableFuture.completedFuture(null);
            if (!session.stopping.compareAndSet(false, true)) return session.stopped;
            if (!session.openAttempted) {
                releaseCapacity(session);
                session.stopped.complete(null);
                return session.stopped;
            }
        }
        try {
            controls.execute(() -> {
                try {
                    closeWorker(session, reason);
                    releaseCapacity(session);
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

    private void stopAndAwait(Session session, String reason) {
        try {
            stop(session, reason).get(closeTimeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException)
                Thread.currentThread().interrupt();
            throw new WorkerUnavailableException();
        }
    }

    private synchronized void releaseCapacity(Session session) {
        session.capacityReleased = true;
        if (session.detached) sessions.remove(session.key, session);
    }

    private void closeWorker(Session session, String reason) {
        long deadline = System.nanoTime() + closeTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode response = worker.request(
                    session.hello, session.identity(), "CLOSE", Map.of("reason", reason), Duration.ofSeconds(3));
            requireOk(response, session);
            String state = response.path("state").asString("");
            if (state.equals("CLOSED")) return;
            if (!state.equals("CLOSING")) throw new WorkerUnavailableException();
            pause();
        }
        throw new WorkerUnavailableException();
    }

    @EventListener
    void closed(McpSessionClosed event) {
        SessionKey key = new SessionKey(event.keyId(), event.workspaceId(), hash(event.sessionId()));
        Session session;
        synchronized (this) {
            session = sessions.get(key);
            if (session != null) {
                session.detached = true;
                if (session.capacityReleased) sessions.remove(key, session);
            }
        }
        if (session != null) stopAndAwait(session, "session_closed");
    }

    @EventListener
    void revoked(AuthRevocation event) {
        List<Session> affected;
        synchronized (this) {
            affected = sessions.values().stream()
                    .filter(session -> session.key.workspace().equals(event.workspaceId())
                            && (event.apiKeyIds().contains(session.key.principal())
                                    || event.accountIds().contains(session.principal.accountId())))
                    .toList();
        }
        affected.forEach(session -> stop(session, "session_closed"));
        try {
            WorkerClient.Hello hello = worker.hello();
            UUID zero = new UUID(0, 0);
            var identity =
                    new WorkerClient.Identity(zero, zero, event.workspaceId().value(), "0".repeat(64), zero);
            long deadline = System.nanoTime() + closeTimeout.toNanos();
            while (true) {
                JsonNode response = worker.request(
                        hello,
                        identity,
                        "REVOKE",
                        Map.of("keyIds", event.apiKeyIds(), "accountIds", event.accountIds()),
                        Duration.ofSeconds(3));
                if (!response.path("ok").booleanValue()) throw new WorkerUnavailableException();
                String state = response.path("state").asString("");
                if (state.equals("CLOSED")) break;
                if (!state.equals("CLOSING") || System.nanoTime() >= deadline) throw new WorkerUnavailableException();
                pause();
            }
            for (Session session : affected) stopAndAwait(session, "session_closed");
        } catch (RuntimeException exception) {
            log.error("Revoked worker process-tree termination is unconfirmed; matching leases are no longer renewed");
            throw exception;
        }
    }

    @Override
    public void close() {
        List<Session> remaining;
        synchronized (this) {
            closed = true;
            remaining = new ArrayList<>(sessions.values());
            sessions.clear();
        }
        heartbeat.shutdownNow();
        remaining.forEach(session -> stop(session, "client_shutdown"));
        for (Session session : remaining) {
            try {
                stopAndAwait(session, "client_shutdown");
            } catch (RuntimeException exception) {
                log.warn("Worker shutdown termination not acknowledged; lease renewal stopped");
            }
        }
        controls.shutdownNow();
    }

    private static void requireLive(Session session) {
        if (session.stopping.get()) throw new WorkerUnavailableException();
    }

    private static void requireOk(JsonNode response, Session session) {
        if (!response.path("ok").booleanValue()) {
            String code = response.path("code").asString("INVALID_RESPONSE");
            log.warn(
                    "Isolated worker rejected an operation: {}",
                    code.matches("[A-Z_]{1,64}") ? code : "INVALID_RESPONSE");
        }
        if (!response.path("ok").booleanValue()
                || !response.path("leaseId").asString("").equals(session.leaseId.toString())
                || (response.hasNonNull("commit")
                        && !response.path("commit").asString("").equals(session.commit)))
            throw new WorkerUnavailableException();
    }

    private static ExecutionResult result(JsonNode result, String commit) {
        try {
            String stdout = result.path("stdout").stringValue();
            String stderr = result.path("stderr").stringValue();
            if (!result.path("commit").asString("").equals(commit)
                    || stdout == null
                    || stderr == null
                    || stdout.getBytes(StandardCharsets.UTF_8).length + stderr.getBytes(StandardCharsets.UTF_8).length
                            > 3 * 64 * 1024
                    || !result.path("exitCode").isIntegralNumber()
                    || !result.path("stdoutTruncated").isBoolean()
                    || !result.path("stderrTruncated").isBoolean()
                    || !result.path("timedOut").isBoolean()) throw new WorkerUnavailableException();
            TerminationReason reason =
                    switch (result.path("terminationReason").stringValue()) {
                        case "session_closed", "client_shutdown" -> TerminationReason.CANCELLED;
                        case "lease_expired" -> TerminationReason.SANDBOX_FAILURE;
                        default ->
                            TerminationReason.valueOf(result.path("terminationReason")
                                    .stringValue()
                                    .toUpperCase(Locale.ROOT));
                    };
            boolean timedOut = result.path("timedOut").booleanValue();
            if (timedOut != (reason == TerminationReason.TIMEOUT)) throw new WorkerUnavailableException();
            return new ExecutionResult(
                    commit,
                    result.path("exitCode").intValue(),
                    stdout,
                    stderr,
                    result.path("stdoutTruncated").booleanValue(),
                    result.path("stderrTruncated").booleanValue(),
                    timedOut,
                    reason);
        } catch (RuntimeException exception) {
            throw new WorkerUnavailableException();
        }
    }

    private static String hash(String session) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(session.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new WorkerUnavailableException();
        }
    }

    private record SessionKey(UUID principal, WorkspaceId workspace, String sessionHash) {}

    private static final class Session {
        private final SessionKey key;
        private final AuthPrincipal principal;
        private final UUID leaseId = UUID.randomUUID();
        private final AtomicBoolean busy = new AtomicBoolean();
        private final AtomicBoolean stopping = new AtomicBoolean();
        private final AtomicBoolean renewing = new AtomicBoolean();
        private final CompletableFuture<Void> stopped = new CompletableFuture<>();
        private volatile WorkerClient.Hello hello;
        private volatile String commit;
        private volatile boolean openAttempted;
        private volatile boolean ready;
        private volatile boolean capacityReleased;
        private volatile boolean detached;
        private volatile long nextRenew;

        private Session(SessionKey key, AuthPrincipal principal) {
            this.key = key;
            this.principal = principal;
        }

        private WorkerClient.Identity identity() {
            return new WorkerClient.Identity(
                    key.principal(), principal.accountId(), key.workspace().value(), key.sessionHash(), leaseId);
        }
    }
}
