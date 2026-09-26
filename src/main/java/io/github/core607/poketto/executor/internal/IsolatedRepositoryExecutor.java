package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.SessionWorker.hash;
import static io.github.core607.poketto.executor.internal.SessionWorker.requireLive;
import static io.github.core607.poketto.executor.internal.SessionWorker.requireOk;

import io.github.core607.poketto.assets.MediaFileService;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthRevocation;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PortableContentExports;
import io.github.core607.poketto.content.RepositoryEmptyException;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.ExecutionUnconfirmedException;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.mcp.SessionReplacedException;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import tools.jackson.databind.JsonNode;

/** Owns account-copy admission, command execution and lease containment; the bridge handles CLI operations. */
final class IsolatedRepositoryExecutor implements RepositoryExecutor, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(IsolatedRepositoryExecutor.class);
    private final SessionWorker io;
    private final SessionRepositoryCommands repository;
    private final ExecutorBridge bridge;
    private final WorkerClient worker;
    private final AccountCopyStore accounts;
    private final SessionRegistry registry;
    private final SessionLifecycle lifecycle;
    private final CopyDisposal disposal;
    private final SessionArtifacts artifacts;
    private final Duration closeTimeout;
    private final ThreadPoolExecutor commandIo = new ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4),
            Thread.ofPlatform().daemon().name("poketto-command-io-", 0).factory());

    IsolatedRepositoryExecutor(
            AccountCopyStore accounts,
            PortableContentExports packages,
            MediaFileService media,
            SelectedFileSaves saves,
            AuthService auth,
            RepositorySnapshotExports exports,
            WorkerClient worker,
            int maxSessions,
            Duration openTimeout,
            Duration closeTimeout) {
        this.accounts = Objects.requireNonNull(accounts, "account disk journals are required");
        this.worker = worker;
        this.io = new SessionWorker(auth, exports, worker);
        var files = new SessionFileTransfers(io);
        this.repository = new SessionRepositoryCommands(io, files, saves, exports, openTimeout);
        var mediaCommands = new SessionMediaCommands(io, files, auth, media, saves);
        this.bridge = new ExecutorBridge(io, files, repository, mediaCommands, saves, packages);
        this.registry = new SessionRegistry(maxSessions);
        this.lifecycle = new SessionLifecycle(
                registry, io, worker, exports, packages, saves, accounts, openTimeout, closeTimeout);
        this.closeTimeout = closeTimeout;
        this.disposal = new CopyDisposal(registry, io, lifecycle, worker, accounts, closeTimeout);
        this.artifacts = new SessionArtifacts(registry, io, lifecycle, accounts);
    }

    @Override
    public ExecutionResult execute(
            AuthPrincipal principal,
            WorkspaceId workspace,
            String serverSessionId,
            CopyRequest expected,
            Optional<String> requested,
            String command,
            Duration timeout,
            ExecutionCancellation cancellation) {
        var access = io.authorize(principal, workspace);
        validateExecution(serverSessionId, expected, requested, command, timeout);
        var owner = new AccountCopyRecord.Owner(
                principal.accountId(), workspace.value(), access.capabilities().contains(Capability.READ_PRIVATE));
        try (var held = new AccountCommand(accounts, accounts.ownerFor(owner, expected.id()), cancellation)) {
            acquireExecution(cancellation);
            try {
                ExecutionSession session = accountSession(principal, workspace, expected, held, cancellation);
                try {
                    return executeAccount(session, held, requested, command, timeout, cancellation);
                } finally {
                    session.busy.set(false);
                }
            } finally {
                registry.releaseOperation();
            }
        } catch (RuntimeException failure) {
            throw admissionFailure(failure, null);
        }
    }

    private static void validateExecution(
            String transport, CopyRequest expected, Optional<String> requested, String command, Duration timeout) {
        Objects.requireNonNull(expected, "copy request must be present");
        if (transport == null
                || transport.isBlank()
                || transport.length() > 128
                || command == null
                || command.isBlank()
                || command.length() > 16384
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(60)) > 0
                || requested.filter(value -> !value.matches("[0-9a-f]{40}")).isPresent()) {
            throw new IllegalArgumentException("Invalid bounded execution request");
        }
    }

    private void acquireExecution(ExecutionCancellation cancellation) {
        if (cancellation.isCancelled()) {
            throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.BUSY, false);
        }
        try {
            if (!registry.tryAcquireOperation(Duration.ofSeconds(5))) {
                throw registry.rejected("operation_limit");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.BUSY, false, interrupted);
        }
    }

    private ExecutionSession accountSession(
            AuthPrincipal principal,
            WorkspaceId workspace,
            CopyRequest expected,
            AccountCommand held,
            ExecutionCancellation cancellation) {
        ExecutionSession.Key key = lifecycle.keyFor(held.owner());
        var record = held.record();
        registry.requireCopyMatch(record, expected);
        WorkerClient.Hello hello = worker.hello();
        ExecutionSession previous = registry.openSession(key);
        if (record != null
                && previous != null
                && previous.copyId.equals(record.copyId())
                && record.writer().leaseId().equals(previous.leaseId)
                && !previous.stopping.get()
                && previous.principal.subjectId().equals(principal.subjectId())
                && hello.workerBootId().equals(previous.hello.workerBootId())
                && record.phase() == AccountCopyRecord.Phase.READY
                && !accounts.expired(record)) {
            if (!previous.busy.compareAndSet(false, true)) {
                throw registry.rejected("session_busy");
            }
            previous.principal = principal;
            return previous;
        }
        if (record != null) {
            lifecycle.fenceAccount(principal, record, key, hello);
            disposal.requireAuthorization(principal, workspace, cancellation);
            if (record.phase() == AccountCopyRecord.Phase.INITIALIZING && !NEW_COPY.equals(expected.id())) {
                throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.RECOVERY_REQUIRED, false);
            }
            if (accounts.expired(record)
                    || record.phase() == AccountCopyRecord.Phase.INITIALIZING
                    || record.phase() == AccountCopyRecord.Phase.DISCARDING) {
                held.beginDiscard();
                disposal.discardDisk(principal.subjectId(), record, key, hello);
                held.remove();
                record = null;
                registry.requireCopyMatch(null, expected);
            }
        }
        releaseIdleCapacity();
        return reserveAccount(principal, key, hello, record);
    }

    private void releaseIdleCapacity() {
        ExecutionSession idle = registry.idleCandidate();
        if (idle != null) {
            try {
                lifecycle.stopAndAwait(idle, "session_closed");
            } finally {
                idle.busy.set(false);
            }
        }
    }

    private ExecutionSession reserveAccount(
            AuthPrincipal principal, ExecutionSession.Key key, WorkerClient.Hello hello, AccountCopyRecord record) {
        return registry.reserve(key, () -> {
            boolean full = key.scopeHash().equals(hash("full"));
            var current = new ExecutionSession(
                    key, principal, full, record == null ? UUID.randomUUID() : record.copyId(), UUID.randomUUID());
            current.hello = hello;
            if (record != null) {
                current.attaching = true;
                current.commit = record.state().originalCommit();
                current.publicExport = record.publicExport();
                current.accountRecord = record;
            }
            return current;
        });
    }

    private ExecutionResult executeAccount(
            ExecutionSession session,
            AccountCommand held,
            Optional<String> requested,
            String command,
            Duration timeout,
            ExecutionCancellation cancellation) {
        boolean attempted = false;
        try (var registration = cancellation.onCancel(() -> lifecycle.stopAndAwait(session, "cancelled"))) {
            requireLive(session);
            if (held.record() != null
                    && requested.isPresent()
                    && !held.record().state().baseCommit().equals(requested.get())) {
                throw new IllegalArgumentException(
                        "The requested commit differs from the working-copy baseline; synchronize explicitly");
            }
            io.authorize(session);
            boolean opening = !session.ready;
            if (opening) {
                if (session.attaching) {
                    held.bind(lifecycle.writer(session));
                    session.accountRecord = held.record();
                    lifecycle.attach(session);
                } else {
                    lifecycle.initializeCopy(session, requested, held);
                }
            }
            held.bind(lifecycle.writer(session));
            session.saveState = held.state();
            if (opening) {
                repository.refreshGitOnOpen(session);
            }
            UUID execution = UUID.randomUUID();
            held.begin(execution);
            session.accountRecord = held.record();
            attempted = true;
            JsonNode response = executeWithBridge(session, execution.toString(), command, timeout);
            if (WorkerResponses.refused(response, "EXECUTION_CAPACITY")) {
                attempted = false;
                held.refused();
                session.accountRecord = held.record();
                throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.CAPACITY, true);
            }
            requireOk(response, session);
            io.authorize(session);
            // Decode before acknowledging the journal, so malformed completion stays unconfirmed.
            result(response.path("result"), session.copyId.toString(), session.commit, session.gitCommit, held.view());
            held.complete(session.saveState.snapshot());
            session.accountRecord = held.record();
            ExecutionResult result = result(
                    response.path("result"), session.copyId.toString(), session.commit, session.gitCommit, held.view());
            if (!response.path("state").asString("").equals("READY")) {
                lifecycle.stopAndAwait(session, "cancelled");
            }
            return result;
        } catch (RuntimeException failure) {
            containFailedCommand(session, failure);
            if (attempted) {
                log.warn("Account command completion was not confirmed", failure);
                throw new ExecutionUnconfirmedException(
                        session.copyId.toString(), unconfirmedView(session, held), true, failure);
            }
            throw admissionFailure(failure, held.record());
        }
    }

    private static CopyRetention unconfirmedView(ExecutionSession session, AccountCommand held) {
        try {
            return held.view();
        } catch (RetainedCopyException unavailable) {
            var known = session.accountRecord;
            return new CopyRetention(known.expiresAt(), false, known.lastInterruptedCommand());
        }
    }

    private void containFailedCommand(ExecutionSession session, RuntimeException failure) {
        try {
            lifecycle.stopAndAwait(session, "cancelled");
        } catch (RuntimeException closing) {
            failure.addSuppressed(closing);
            log.warn("Account command containment remains unconfirmed", closing);
        }
    }

    static RuntimeException admissionFailure(RuntimeException failure, AccountCopyRecord record) {
        if (failure instanceof ExecutionAdmissionException
                || failure instanceof ExecutionUnconfirmedException
                || failure instanceof AuthException
                || failure instanceof SecurityException
                || failure instanceof IllegalArgumentException
                || failure instanceof SessionReplacedException
                || failure instanceof ContentRepositoryException
                || failure instanceof RepositoryEmptyException) {
            return failure;
        }
        log.warn("Account copy admission failed", failure);
        ExecutionAdmissionException.Reason reason = failure instanceof RetainedCopyException retained
                ? switch (retained.reason()) {
                    case MISSING -> ExecutionAdmissionException.Reason.MISSING_COPY;
                    case EXPIRED -> ExecutionAdmissionException.Reason.EXPIRED;
                    case BUSY -> ExecutionAdmissionException.Reason.BUSY;
                    case LIMIT -> ExecutionAdmissionException.Reason.CAPACITY;
                    case STALE, UNCERTAIN -> ExecutionAdmissionException.Reason.RECOVERY_REQUIRED;
                    case UNAVAILABLE -> ExecutionAdmissionException.Reason.UNAVAILABLE;
                }
                : ExecutionAdmissionException.Reason.UNAVAILABLE;
        boolean available = record != null && record.expiresAt() > System.currentTimeMillis();
        return new ExecutionAdmissionException(reason, available, failure);
    }

    @Override
    public DiscardResult discard(
            AuthPrincipal principal,
            WorkspaceId workspace,
            DiscardRequest request,
            ExecutionCancellation cancellation) {
        return disposal.discard(principal, workspace, request, cancellation);
    }

    int collectExpiredCopies() {
        return disposal.collectExpiredCopies();
    }

    @Override
    public Optional<ArtifactChunk> readArtifact(
            AuthPrincipal principal,
            WorkspaceId workspace,
            String serverSessionId,
            String artifactId,
            long offset,
            int limit,
            ExecutionCancellation cancellation) {
        return artifacts.read(principal, workspace, serverSessionId, artifactId, offset, limit, cancellation);
    }

    void bindMetrics(MeterRegistry meters) {
        registry.bindMetrics(meters);
    }

    // The EXEC request runs on its own thread while this one serves the worker's bridge requests
    // until the command finishes, the session stops, or the worker reports the lease closed.
    private JsonNode executeWithBridge(ExecutionSession session, String executionId, String command, Duration timeout) {
        var running = commandIo.submit(() -> io.request(
                session,
                "EXEC",
                new WorkerRequests.Exec(executionId, session.commit, command, timeout.toMillis()),
                timeout.plusSeconds(5)));
        long deadline = System.nanoTime() + timeout.plusSeconds(5).toNanos();
        try {
            while (!running.isDone() && !session.stopping.get()) {
                Optional<JsonNode> polled = pollBridge(session, deadline);
                if (polled.isEmpty()
                        || running.isDone()
                        || session.stopping.get()
                        || closedByWorker(polled.orElseThrow())) {
                    break;
                }
                requireOk(polled.orElseThrow(), session);
                JsonNode request = polled.orElseThrow().path("bridgeRequest");
                if (!request.isMissingNode() && !request.isNull()) {
                    answerBridgeRequest(session, executionId, polled.orElseThrow(), request);
                }
            }
            // The EXEC reply owns its terminal result; closing the mailbox during cancellation
            // must not turn a confirmed cancelled command into an unknown transport outcome.
            return running.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WorkerUnavailableException(interrupted);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            throw new WorkerUnavailableException();
        } catch (TimeoutException expired) {
            throw new WorkerUnavailableException(expired);
        } finally {
            running.cancel(true);
        }
    }

    // One poll of the worker's bridge mailbox. A poll that fails while the session is stopping
    // ends the loop and leaves the command's own reply to decide the outcome.
    private Optional<JsonNode> pollBridge(ExecutionSession session, long deadline) {
        io.authorize(session);
        if (System.nanoTime() >= deadline) {
            throw new WorkerUnavailableException();
        }
        try {
            return Optional.of(
                    io.request(session, "BRIDGE_POLL", new WorkerRequests.BridgePoll(), Duration.ofSeconds(3)));
        } catch (RuntimeException failed) {
            if (session.stopping.get()) {
                return Optional.empty();
            }
            throw failed;
        }
    }

    private static boolean closedByWorker(JsonNode polled) {
        return !polled.path("ok").asBoolean(false)
                && Set.of("LEASE_EXPIRED", "BRIDGE_UNAVAILABLE", "AUTH_REVOKED")
                        .contains(polled.path("code").asString(""));
    }

    // A bridge request belongs to this execution and names a well-formed request id; the session
    // must still be live and authorized both before and after the reply is computed.
    private void answerBridgeRequest(ExecutionSession session, String executionId, JsonNode polled, JsonNode request) {
        if (!executionId.equals(polled.path("executionId").asString(""))) {
            throw new WorkerUnavailableException();
        }
        String requestId = request.path("requestId").stringValue();
        if (!UUID.fromString(requestId).toString().equals(requestId)) {
            throw new WorkerUnavailableException();
        }
        requireLive(session);
        io.authorize(session);
        var reply = bridge.bridgeReply(session, executionId, request);
        requireLive(session);
        io.authorize(session);
        requireOk(
                io.request(
                        session,
                        "BRIDGE_COMPLETE",
                        new WorkerRequests.BridgeComplete(executionId, requestId, reply),
                        Duration.ofSeconds(3)),
                session);
    }

    @EventListener
    void revoked(AuthRevocation event) {
        List<ExecutionSession> affected = registry.affectedBy(event);
        affected.forEach(session -> lifecycle.stop(session, "session_closed"));
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
                        new WorkerRequests.Revoke(event.apiKeyIds(), event.accountIds()),
                        Duration.ofSeconds(3));
                if (!response.path("ok").booleanValue()) {
                    throw new WorkerUnavailableException();
                }
                String state = response.path("state").asString("");
                if (state.equals("CLOSED")) {
                    break;
                }
                if (!state.equals("CLOSING") || System.nanoTime() >= deadline) {
                    throw new WorkerUnavailableException();
                }
                SessionLifecycle.pause();
            }
            for (ExecutionSession session : affected) {
                lifecycle.stopAndAwait(session, "session_closed");
            }
        } catch (RuntimeException exception) {
            log.error("Revoked worker process-tree termination is unconfirmed; matching leases are no longer renewed");
            throw exception;
        }
    }

    @Override
    public void close() {
        List<ExecutionSession> remaining;
        remaining = registry.drain();
        lifecycle.stopHeartbeat();
        remaining.forEach(session -> lifecycle.stop(session, "client_shutdown"));
        for (ExecutionSession session : remaining) {
            try {
                lifecycle.stopAndAwait(session, "client_shutdown");
            } catch (RuntimeException exception) {
                log.warn("Worker shutdown termination not acknowledged; lease renewal stopped");
            }
        }
        lifecycle.shutdownControls();
        commandIo.shutdownNow();
    }

    private static ExecutionResult result(
            JsonNode result, String copyId, String commit, String gitCommit, CopyRetention retention) {
        try {
            var finished = WorkerResponses.read(result, WorkerResponses.Execution.class);
            // The commit is an echo of what this session pinned, so it is compared here.
            if (!result.path("commit").asString("").equals(commit)) {
                throw new WorkerUnavailableException();
            }
            TerminationReason reason = finished.reason();
            if (!result.path("artifacts").isObject()
                    || !result.path("artifactErrors").isObject()
                    || result.path("artifacts").size() > 2
                    || result.path("artifactErrors").size() > 2) {
                throw new WorkerUnavailableException();
            }
            Map<String, ArtifactMetadata> artifacts = new LinkedHashMap<>();
            Map<String, String> artifactErrors = new LinkedHashMap<>();
            for (var entry : result.path("artifacts").properties()) {
                if (!Set.of("stdout", "stderr").contains(entry.getKey())) {
                    throw new WorkerUnavailableException();
                }
                artifacts.put(entry.getKey(), ExecutorBridge.artifactMetadata(entry.getValue()));
            }
            for (var entry : result.path("artifactErrors").properties()) {
                if (!Set.of("stdout", "stderr").contains(entry.getKey())
                        || artifacts.containsKey(entry.getKey())
                        || !Set.of("ARTIFACT_UNAVAILABLE", "ARTIFACT_CAPACITY")
                                .contains(entry.getValue().asString(""))) {
                    throw new WorkerUnavailableException();
                }
                artifactErrors.put(entry.getKey(), entry.getValue().stringValue());
            }
            return new ExecutionResult(
                    copyId,
                    gitCommit,
                    finished.exitCode(),
                    finished.stdout(),
                    finished.stderr(),
                    finished.stdoutTruncated(),
                    finished.stderrTruncated(),
                    finished.timedOut(),
                    finished.freshSandbox(),
                    reason,
                    artifacts,
                    artifactErrors,
                    retention);
        } catch (RuntimeException exception) {
            String reason = result.path("terminationReason").asString("");
            log.warn(
                    // The exception's own text can quote the answer, which may hold command
                    // output, so only its type and the reason's vetted shape are recorded.
                    "Invalid worker execution result ({}; termination={})",
                    exception.getClass().getSimpleName(),
                    reason.matches("[a-z_]{1,32}") ? reason : "invalid");
            throw new WorkerUnavailableException();
        }
    }
}
