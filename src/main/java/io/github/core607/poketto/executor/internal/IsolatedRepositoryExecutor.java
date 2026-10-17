package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.SessionWorker.requireLive;
import static io.github.core607.poketto.executor.internal.SessionWorker.requireOk;

import io.github.core607.poketto.assets.MediaFileService;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthRevocation;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.SiteGroup;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import tools.jackson.databind.JsonNode;

/** Owns account-copy admission, command execution and lease containment; the bridge handles CLI operations. */
final class IsolatedRepositoryExecutor implements RepositoryExecutor, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(IsolatedRepositoryExecutor.class);
    private final SessionWorker io;
    private final SessionRepositoryCommands repository;
    private final BridgedCommands commands;
    private final WorkerClient worker;
    private final AccountCopyStore accounts;
    private final SessionRegistry registry;
    private final AccountOperations accountOperations;
    private final Map<SiteGroup, Integer> copyQuotas;
    private final Function<AuthPrincipal, SiteGroup> groups;
    private final SessionLifecycle lifecycle;
    private final CopyDisposal disposal;
    private final PublicCopyRefresh refresh;
    private final SessionArtifacts artifacts;
    private final Duration closeTimeout;
    // One virtual thread per EXEC in flight; the operation permits already bound how many there are,
    // so a second, smaller bound here would refuse commands the registry admitted.
    private final ExecutorService commandIo = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("poketto-command-io-", 0).factory());

    IsolatedRepositoryExecutor(
            AccountCopyStore accounts,
            PortableContentExports packages,
            MediaFileService media,
            SelectedFileSaves saves,
            AuthService auth,
            RepositorySnapshotExports exports,
            WorkerClient worker,
            ExecutionLimits limits,
            Duration openTimeout,
            Duration closeTimeout) {
        this.accounts = Objects.requireNonNull(accounts, "account disk journals are required");
        this.worker = worker;
        this.io = new SessionWorker(auth, exports, worker);
        var files = new SessionFileTransfers(io);
        this.repository = new SessionRepositoryCommands(io, files, saves, exports, openTimeout);
        var mediaCommands = new SessionMediaCommands(io, files, auth, media, saves);
        this.commands = new BridgedCommands(
                io, new ExecutorBridge(io, files, repository, mediaCommands, saves, packages), commandIo);
        this.registry = new SessionRegistry(limits.maxSessions(), limits.maxOperations());
        this.accountOperations = new AccountOperations(limits.accountOperations(), auth::siteGroup);
        this.copyQuotas = limits.accountCopies();
        this.groups = auth::siteGroup;
        this.lifecycle = new SessionLifecycle(
                registry, io, worker, exports, packages, saves, accounts, openTimeout, closeTimeout);
        this.closeTimeout = closeTimeout;
        this.disposal = new CopyDisposal(registry, io, lifecycle, worker, accounts, closeTimeout);
        this.refresh = new PublicCopyRefresh(registry, io, lifecycle, disposal, exports, worker);
        this.artifacts = new SessionArtifacts(registry, io, lifecycle, accounts);
    }

    @Override
    public ExecutionResult execute(
            AuthPrincipal principal,
            WorkspaceId workspace,
            CopyRequest expected,
            Optional<String> requested,
            String command,
            Duration timeout,
            ExecutionCancellation cancellation) {
        var timings = new ExecutionTimings();
        try {
            var access = io.authorize(principal, workspace);
            validateExecution(expected, requested, command, timeout);
            timings.end(ExecutionTimings.Stage.AUTHORIZE);
            var owner = new AccountCopyRecord.Owner(
                    principal.accountId(),
                    workspace.value(),
                    access.capabilities().contains(Capability.READ_PRIVATE));
            try (var held = command(principal, owner, expected, cancellation)) {
                timings.end(ExecutionTimings.Stage.JOURNAL);
                var slot = acquireExecution(principal, cancellation);
                timings.end(ExecutionTimings.Stage.PERMIT);
                try {
                    var admitted = refresh.current(
                            accountSession(principal, workspace, expected, held, cancellation), held, cancellation);
                    timings.end(ExecutionTimings.Stage.ADMIT);
                    ExecutionSession session = admitted.session();
                    try {
                        return executeAccount(
                                session,
                                held,
                                requested,
                                command,
                                timeout,
                                cancellation,
                                admitted.refreshed(),
                                timings);
                    } finally {
                        session.busy.set(false);
                    }
                } finally {
                    registry.releaseOperation();
                    slot.close();
                }
            } catch (RuntimeException failure) {
                timings.failed();
                throw admissionFailure(failure, null);
            }
        } finally {
            timings.record();
        }
    }

    private static void validateExecution(
            CopyRequest expected, Optional<String> requested, String command, Duration timeout) {
        Objects.requireNonNull(expected, "copy request must be present");
        if (command == null
                || command.isBlank()
                || command.length() > 16384
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(60)) > 0
                || requested.filter(value -> !value.matches("[0-9a-f]{40}")).isPresent()) {
            throw new IllegalArgumentException("Invalid bounded execution request");
        }
    }

    /**
     * The command's copy. {@code new} takes the account's default copy unless another command
     * holds it; then, within the account's copy quota, it opens a fork, so agents that start in
     * parallel do not queue behind each other. A named copy waits for its own journal.
     */
    private AccountCommand command(
            AuthPrincipal principal,
            AccountCopyRecord.Owner owner,
            CopyRequest expected,
            ExecutionCancellation cancellation) {
        if (!NEW_COPY.equals(expected.id())) {
            return new AccountCommand(accounts, accounts.ownerFor(owner, expected.id()), cancellation);
        }
        var idle = AccountCommand.ifIdle(accounts, owner);
        if (idle.isPresent()) {
            return idle.get();
        }
        Integer quota = copyQuotas.isEmpty() ? null : copyQuotas.get(groups.apply(principal));
        if (quota == null || accounts.copiesOf(owner.accountId(), owner.workspaceId()) < quota) {
            return new AccountCommand(accounts, owner.forked(UUID.randomUUID()), cancellation);
        }
        return new AccountCommand(accounts, owner, cancellation);
    }

    /** The account's slot, then a site-wide permit; each waits at most five seconds. */
    private AccountOperations.Slot acquireExecution(AuthPrincipal principal, ExecutionCancellation cancellation) {
        if (cancellation.isCancelled()) {
            throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.BUSY, false);
        }
        try {
            var slot = accountOperations.acquire(principal, Duration.ofSeconds(5));
            if (slot == null) {
                throw registry.rejected("account_limit");
            }
            boolean permitted = false;
            try {
                permitted = registry.tryAcquireOperation(Duration.ofSeconds(5));
            } finally {
                if (!permitted) {
                    slot.close();
                }
            }
            if (!permitted) {
                throw registry.rejected("operation_limit");
            }
            return slot;
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
        return reserveAccount(principal, held.owner(), key, hello, record);
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
            AuthPrincipal principal,
            AccountCopyRecord.Owner owner,
            ExecutionSession.Key key,
            WorkerClient.Hello hello,
            AccountCopyRecord record) {
        return registry.reserve(key, () -> {
            var current = new ExecutionSession(
                    key,
                    principal,
                    owner.fullRead(),
                    record == null ? owner.newCopyId() : record.copyId(),
                    UUID.randomUUID());
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
            ExecutionCancellation cancellation,
            boolean refreshed,
            ExecutionTimings timings) {
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
            timings.end(ExecutionTimings.Stage.PREPARE);
            JsonNode response = commands.execute(session, execution.toString(), command, timeout, timings);
            timings.end(ExecutionTimings.Stage.COMMAND);
            if (WorkerResponses.refused(response, "EXECUTION_CAPACITY")) {
                attempted = false;
                held.refused();
                session.accountRecord = held.record();
                throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.CAPACITY, true);
            }
            requireOk(response, session);
            io.authorize(session);
            // Decode before acknowledging the journal, so malformed completion stays unconfirmed.
            result(response.path("result"), session, refreshed, held.view());
            held.complete(session.saveState.snapshot());
            session.accountRecord = held.record();
            ExecutionResult result = result(response.path("result"), session, refreshed, held.view());
            timings.end(ExecutionTimings.Stage.COMPLETE);
            if (!response.path("state").asString("").equals("READY")) {
                lifecycle.stopAndAwait(session, "cancelled");
            }
            return result;
        } catch (RuntimeException failure) {
            containFailedCommand(session, failure, timings);
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

    private void containFailedCommand(ExecutionSession session, RuntimeException failure, ExecutionTimings timings) {
        timings.failed();
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
            String artifactId,
            long offset,
            int limit,
            ExecutionCancellation cancellation) {
        return artifacts.read(principal, workspace, artifactId, offset, limit, cancellation);
    }

    void bindMetrics(MeterRegistry meters) {
        registry.bindMetrics(meters);
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
                if (!state.equals("CLOSING") || System.nanoTime() - deadline >= 0) {
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
        commands.close();
    }

    private static ExecutionResult result(
            JsonNode result, ExecutionSession session, boolean refreshed, CopyRetention retention) {
        String commit = session.commit;
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
                    session.copyId.toString(),
                    session.gitCommit,
                    finished.exitCode(),
                    finished.stdout(),
                    finished.stderr(),
                    finished.stdoutTruncated(),
                    finished.stderrTruncated(),
                    finished.timedOut(),
                    finished.freshSandbox(),
                    refreshed,
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
