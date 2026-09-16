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
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.ExecutionUnconfirmedException;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.mcp.SessionReplacedException;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import tools.jackson.databind.JsonNode;

/** Owns account-copy admission, command execution and lease containment; the bridge handles CLI operations. */
final class IsolatedRepositoryExecutor implements RepositoryExecutor, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(IsolatedRepositoryExecutor.class);
    private final RepositorySnapshotExports exports;
    private final PortableContentExports packages;
    private final SessionWorker io;
    private final SessionRepositoryCommands repository;
    private final ExecutorBridge bridge;
    private final WorkerClient worker;
    private final SelectedFileSaves saves;
    private final AccountCopyStore accounts;
    private final int maxSessions;
    private final Duration openTimeout;
    private final Duration closeTimeout;
    private final Map<ExecutionSession.Key, ExecutionSession> sessions = new LinkedHashMap<>();
    private final Map<UUID, ExecutionSession> closingLeases = new LinkedHashMap<>();
    private final Semaphore executions = new Semaphore(4, true);
    private final LongAdder createdCopies = new LongAdder();
    private final LongAdder releasedCopies = new LongAdder();
    private final Map<String, LongAdder> rejected = Map.of(
            "copy_mismatch", new LongAdder(),
            "session_limit", new LongAdder(),
            "operation_limit", new LongAdder(),
            "session_busy", new LongAdder());

    private final ThreadPoolExecutor commandIo = new ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4),
            Thread.ofPlatform().daemon().name("poketto-command-io-", 0).factory());
    private final ThreadPoolExecutor controls = new ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128),
            Thread.ofPlatform().daemon().name("poketto-worker-control-", 0).factory());
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("poketto-worker-heartbeat").factory());
    private volatile boolean closed;

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
        this.packages = packages;
        this.saves = saves;
        this.exports = exports;
        this.worker = worker;
        this.io = new SessionWorker(auth, exports, worker);
        var files = new SessionFileTransfers(io);
        this.repository = new SessionRepositoryCommands(io, files, saves, exports, openTimeout);
        var mediaCommands = new SessionMediaCommands(io, files, auth, media, saves);
        this.bridge = new ExecutorBridge(io, files, repository, mediaCommands, saves, packages);
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
                executions.release();
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
            if (!executions.tryAcquire(5, TimeUnit.SECONDS)) {
                throw rejected("operation_limit");
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
        ExecutionSession.Key key = keyFor(held.owner());
        var record = held.record();
        requireAccountRequest(record, expected);
        WorkerClient.Hello hello = worker.hello();
        ExecutionSession previous;
        synchronized (this) {
            if (closed) {
                throw new WorkerUnavailableException();
            }
            previous = sessions.get(key);
        }
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
                throw rejected("session_busy");
            }
            previous.principal = principal;
            return previous;
        }
        if (record != null) {
            fenceAccount(principal, record, key, hello);
            requireDiscardAuthorization(principal, workspace, cancellation);
            if (record.phase() == AccountCopyRecord.Phase.INITIALIZING && !NEW_COPY.equals(expected.id())) {
                throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.RECOVERY_REQUIRED, false);
            }
            if (accounts.expired(record)
                    || record.phase() == AccountCopyRecord.Phase.INITIALIZING
                    || record.phase() == AccountCopyRecord.Phase.DISCARDING) {
                held.beginDiscard();
                discardDisk(principal.subjectId(), record, key, hello);
                held.remove();
                record = null;
                requireAccountRequest(null, expected);
            }
        }
        releaseIdleCapacity();
        return reserveAccount(principal, key, hello, record);
    }

    private void releaseIdleCapacity() {
        ExecutionSession idle = null;
        synchronized (this) {
            if (activeSessions() < maxSessions) {
                return;
            }
            for (ExecutionSession candidate : sessions.values()) {
                if (!candidate.capacityReleased && candidate.busy.compareAndSet(false, true)) {
                    idle = candidate;
                    break;
                }
            }
        }
        if (idle != null) {
            try {
                stopAndAwait(idle, "session_closed");
            } finally {
                idle.busy.set(false);
            }
        }
    }

    private synchronized ExecutionSession reserveAccount(
            AuthPrincipal principal, ExecutionSession.Key key, WorkerClient.Hello hello, AccountCopyRecord record) {
        if (closed || activeSessions() >= maxSessions || sessions.size() >= 1024) {
            throw rejected("session_limit");
        }
        boolean full = key.scopeHash().equals(hash("full"));
        var current = new ExecutionSession(
                key, principal, full, record == null ? UUID.randomUUID() : record.copyId(), UUID.randomUUID());
        current.hello = hello;
        current.busy.set(true);
        if (record != null) {
            current.attaching = true;
            current.commit = record.state().originalCommit();
            current.publicExport = record.publicExport();
            current.accountRecord = record;
        }
        sessions.put(key, current);
        createdCopies.increment();
        return current;
    }

    private void requireAccountRequest(AccountCopyRecord record, CopyRequest expected) {
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

    private ExecutionResult executeAccount(
            ExecutionSession session,
            AccountCommand held,
            Optional<String> requested,
            String command,
            Duration timeout,
            ExecutionCancellation cancellation) {
        boolean attempted = false;
        try (var registration = cancellation.onCancel(() -> stopAndAwait(session, "cancelled"))) {
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
                    held.bind(writer(session));
                    session.accountRecord = held.record();
                    attach(session);
                } else {
                    initializeCopy(session, requested, held);
                }
            }
            held.bind(writer(session));
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
                stopAndAwait(session, "cancelled");
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
            stopAndAwait(session, "cancelled");
        } catch (RuntimeException closing) {
            failure.addSuppressed(closing);
            log.warn("Account command containment remains unconfirmed", closing);
        }
    }

    private AccountCopyRecord.Writer writer(ExecutionSession session) {
        return new AccountCopyRecord.Writer(
                session.principal.subjectId(),
                session.hello.workerBootId(),
                worker.applicationBootId(),
                session.leaseId);
    }

    private void fenceAccount(
            AuthPrincipal principal, AccountCopyRecord record, ExecutionSession.Key key, WorkerClient.Hello hello) {
        ExecutionSession control;
        synchronized (this) {
            control = sessions.get(key);
            if (control == null || !control.leaseId.equals(record.writer().leaseId())) {
                control = closingLeases.get(record.writer().leaseId());
            }
            if (control == null) {
                control = new ExecutionSession(
                        key,
                        principal,
                        record.owner().fullRead(),
                        record.copyId(),
                        record.writer().leaseId());
                control.auxiliary = true;
                control.retainedAppBoot = record.writer().appBootId();
                control.priorPrincipal = record.writer().principalId();
                control.commit = record.state().originalCommit();
                control.publicExport = record.publicExport();
                control.hello = hello;
                control.openAttempted = true;
                closingLeases.put(control.leaseId, control);
            }
        }
        if (record.writer().workerBootId().equals(hello.workerBootId())) {
            stopAndAwait(control, "session_closed");
        } else {
            control.stopping.set(true);
            releaseCapacity(control);
            control.stopped.complete(null);
        }
        synchronized (this) {
            sessions.remove(key, control);
        }
    }

    @Override
    public DiscardResult discard(
            AuthPrincipal principal,
            WorkspaceId workspace,
            DiscardRequest request,
            ExecutionCancellation cancellation) {
        requireDiscardAuthorization(principal, workspace, cancellation);
        // Cleanup remains available when read authority has been withdrawn; no content is returned.
        for (boolean full : List.of(true, false)) {
            var owner = new AccountCopyRecord.Owner(principal.accountId(), workspace.value(), full);
            try (var held = new AccountCommand(accounts, owner, cancellation)) {
                var record = held.record();
                if (record == null || !record.copyId().toString().equals(request.id())) {
                    continue;
                }
                if (record.phase() != AccountCopyRecord.Phase.DISCARDING) {
                    requireAccountRequest(record, new CopyRequest(request.id()));
                }
                held.beginDiscard();
                var key = new ExecutionSession.Key(principal.accountId(), workspace, hash(full ? "full" : "public"));
                var hello = worker.hello();
                fenceAccount(principal, record, key, hello);
                requireDiscardAuthorization(principal, workspace, cancellation);
                discardDisk(principal.subjectId(), record, key, hello);
                held.remove();
                return new DiscardResult(request.id(), DiscardStatus.DISCARDED);
            } catch (RuntimeException failure) {
                throw admissionFailure(failure, null);
            }
        }
        return new DiscardResult(request.id(), DiscardStatus.ABSENT);
    }

    private void discardDisk(
            UUID principalId, AccountCopyRecord record, ExecutionSession.Key key, WorkerClient.Hello hello) {
        var identity = new WorkerClient.Identity(
                principalId, key.account(), key.workspace().value(), key.scopeHash(), UUID.randomUUID());
        JsonNode response = worker.request(
                hello,
                identity,
                "DISCARD",
                new WorkerRequests.DiskCopy(
                        record.copyId(),
                        record.owner().fullRead() ? "full" : "public",
                        record.state().originalCommit()),
                closeTimeout);
        if (!response.path("ok").asBoolean()
                || !identity.leaseId()
                        .toString()
                        .equals(response.path("leaseId").asString())
                || !Set.of("DISCARDED", "ABSENT")
                        .contains(response.path("state").asString(""))) {
            throw new WorkerUnavailableException();
        }
    }

    int collectExpiredCopies() {
        int removed = 0;
        for (var owner : accounts.collectionCandidates(8)) {
            if (closed || Thread.currentThread().isInterrupted()) {
                break;
            }
            try (var lease = accounts.acquire(owner)) {
                var record = lease.record().orElse(null);
                if (record == null || !accounts.collectible(record)) {
                    continue;
                }
                if (record.phase() != AccountCopyRecord.Phase.DISCARDING) {
                    lease.write(record.discarding());
                }
                var key = new ExecutionSession.Key(
                        owner.accountId(),
                        new WorkspaceId(owner.workspaceId()),
                        hash(owner.fullRead() ? "full" : "public"));
                var hello = worker.hello();
                fenceCollectedAccount(record, key, hello);
                discardDisk(record.writer().principalId(), record, key, hello);
                lease.remove(record.copyId());
                removed++;
            } catch (RetainedCopyException busy) {
                if (busy.reason() != RetainedCopyException.Reason.BUSY) {
                    log.warn("Account copy collection will retry", busy);
                }
            } catch (IOException | WorkerUnavailableException unavailable) {
                log.warn("Account copy collection awaits confirmed cleanup", unavailable);
            }
        }
        return removed;
    }

    private void fenceCollectedAccount(AccountCopyRecord record, ExecutionSession.Key key, WorkerClient.Hello hello) {
        ExecutionSession current;
        synchronized (this) {
            current = sessions.get(key);
        }
        if (current != null && current.copyId.equals(record.copyId())) {
            fenceAccount(current.principal, record, key, hello);
            return;
        }
        if (!record.writer().workerBootId().equals(hello.workerBootId())) {
            return;
        }
        var identity = new WorkerClient.Identity(
                record.writer().principalId(),
                key.account(),
                key.workspace().value(),
                key.scopeHash(),
                record.writer().leaseId());
        long deadline = System.nanoTime() + closeTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode response = worker.closeRetainedLease(
                    hello, identity, record.writer().appBootId(), "session_closed", Duration.ofSeconds(3));
            if (!response.path("ok").asBoolean()
                    || !identity.leaseId()
                            .toString()
                            .equals(response.path("leaseId").asString())) {
                throw new WorkerUnavailableException();
            }
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

    private void requireDiscardAuthorization(
            AuthPrincipal principal, WorkspaceId workspace, ExecutionCancellation cancellation) {
        io.authorize(principal, workspace);
        if (closed || cancellation.isCancelled()) {
            throw new WorkerUnavailableException();
        }
    }

    private static RuntimeException admissionFailure(RuntimeException failure, AccountCopyRecord record) {
        if (failure instanceof ExecutionAdmissionException
                || failure instanceof ExecutionUnconfirmedException
                || failure instanceof AuthException
                || failure instanceof SecurityException
                || failure instanceof IllegalArgumentException
                || failure instanceof SessionReplacedException
                || failure instanceof ContentRepositoryException) {
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
    public Optional<ArtifactChunk> readArtifact(
            AuthPrincipal principal,
            WorkspaceId workspace,
            String serverSessionId,
            String artifactId,
            long offset,
            int limit,
            ExecutionCancellation cancellation) {
        var access = io.authorize(principal, workspace);
        validateArtifactRequest(serverSessionId, artifactId, offset, limit);
        List<Boolean> scopes =
                access.capabilities().contains(Capability.READ_PRIVATE) ? List.of(true, false) : List.of(false);
        for (boolean full : scopes) {
            var owner = new AccountCopyRecord.Owner(principal.accountId(), workspace.value(), full);
            try (var held = new AccountCommand(accounts, owner, cancellation)) {
                if (held.record() == null || accounts.expired(held.record())) {
                    continue;
                }
                held.requireLive();
                var page = readAccountArtifact(
                        principal, workspace, serverSessionId, artifactId, offset, limit, cancellation, held);
                if (page.isPresent()) {
                    return page;
                }
            } catch (RuntimeException failure) {
                throw admissionFailure(failure, null);
            }
        }
        return Optional.empty();
    }

    private Optional<ArtifactChunk> readAccountArtifact(
            AuthPrincipal principal,
            WorkspaceId workspace,
            String serverSessionId,
            String artifactId,
            long offset,
            int limit,
            ExecutionCancellation cancellation,
            AccountCommand held) {
        io.authorize(principal, workspace);
        validateArtifactRequest(serverSessionId, artifactId, offset, limit);
        ExecutionSession session;
        synchronized (this) {
            session = sessions.get(keyFor(held.owner()));
        }
        if (session == null) {
            return Optional.empty();
        }
        if (cancellation.isCancelled()) {
            throw new WorkerUnavailableException();
        }
        if (!executions.tryAcquire()) {
            throw rejected("operation_limit");
        }
        boolean ownsRead = false;
        try {
            if (!session.busy.compareAndSet(false, true)) {
                throw rejected("session_busy");
            }
            ownsRead = true;
            session = refreshLease(session, principal);
            held.bind(writer(session));
            session.accountRecord = held.record();
            ExecutionSession active = session;
            try (var registration = cancellation.onCancel(() -> stopAndAwait(active, "cancelled"))) {
                requireLive(session);
                io.authorize(session);
                if (!session.ready && session.attaching) {
                    attach(session);
                }
                if (!session.ready) {
                    throw new WorkerUnavailableException();
                }
                var page = readArtifactPage(session, artifactId, offset, limit);
                if (page.isPresent()) {
                    held.complete(held.record().state());
                    session.accountRecord = held.record();
                }
                return page;
            }
        } catch (RuntimeException failure) {
            if (ownsRead && !(failure instanceof IllegalArgumentException)) {
                stop(session, "cancelled");
            }
            throw failure;
        } finally {
            if (ownsRead) {
                session.busy.set(false);
            }
            executions.release();
        }
    }

    private static void validateArtifactRequest(String serverSessionId, String artifactId, long offset, int limit) {
        if (serverSessionId == null
                || serverSessionId.isBlank()
                || serverSessionId.length() > 128
                || artifactId == null
                || !UUID.fromString(artifactId).toString().equals(artifactId)
                || offset < 0
                || offset > 128L * 1024 * 1024
                || limit < 1
                || limit > 65536) {
            throw new IllegalArgumentException("Invalid artifact read");
        }
    }

    private Optional<ArtifactChunk> readArtifactPage(
            ExecutionSession session, String artifactId, long offset, int limit) {
        JsonNode response = io.request(
                session,
                "ARTIFACT_READ",
                new WorkerRequests.ArtifactRead(artifactId, offset, limit),
                Duration.ofSeconds(3));
        requireLive(session);
        io.authorize(session);
        String code = response.path("code").asString("");
        if (code.equals("ARTIFACT_UNAVAILABLE")) {
            return Optional.empty();
        }
        if (code.equals("INVALID_ARTIFACT_RANGE")) {
            throw new IllegalArgumentException("Invalid artifact range");
        }
        requireOk(response, session);
        var metadata = ExecutorBridge.artifactMetadata(response);
        var page = WorkerResponses.read(response, WorkerResponses.ArtifactPage.class);
        long size = metadata.bytes();
        // The identifier and the offset are echoes of this request.
        if (!artifactId.equals(metadata.artifactId()) || offset > size || page.offset() != offset) {
            throw new WorkerUnavailableException();
        }
        byte[] bytes = page.decoded();
        if (bytes.length != Math.min(limit, size - offset)) {
            throw new WorkerUnavailableException();
        }
        return Optional.of(new ArtifactChunk(
                artifactId,
                metadata.name(),
                metadata.mediaType(),
                size,
                metadata.sha256(),
                metadata.truncated(),
                metadata.expiresInSeconds(),
                offset,
                bytes));
    }

    private ExecutionAdmissionException rejected(String reason) {
        rejected.get(reason).increment();
        return new ExecutionAdmissionException(
                reason.equals("session_limit")
                        ? ExecutionAdmissionException.Reason.CAPACITY
                        : ExecutionAdmissionException.Reason.BUSY,
                false);
    }

    void bindMetrics(MeterRegistry registry) {
        registry.gauge("poketto.executor.sessions.active", this, IsolatedRepositoryExecutor::activeSessions);
        registry.gauge("poketto.executor.operations.active", this, value -> 4 - value.executions.availablePermits());
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

    private void initializeCopy(ExecutionSession session, Optional<String> requested, AccountCommand held) {
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

    private static ExecutionSession.Key keyFor(AccountCopyRecord.Owner owner) {
        return new ExecutionSession.Key(
                owner.accountId(), new WorkspaceId(owner.workspaceId()), hash(owner.fullRead() ? "full" : "public"));
    }

    private ExecutionSession refreshLease(ExecutionSession previous, AuthPrincipal principal) {
        if (!previous.stopping.get() && previous.principal.subjectId().equals(principal.subjectId())) {
            previous.principal = principal;
            return previous;
        }
        WorkerClient.Hello hello = worker.hello();
        if (previous.hello != null && !previous.hello.workerBootId().equals(hello.workerBootId())) {
            previous.stopping.set(true);
            releaseCapacity(previous);
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
        synchronized (this) {
            if (closed || !sessions.replace(previous.key, previous, current)) {
                throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.UNAVAILABLE, true);
            }
        }
        previous.busy.set(false);
        return current;
    }

    private void attach(ExecutionSession session) {
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
        List<ExecutionSession> current;
        synchronized (this) {
            current = new ArrayList<>(sessions.values());
            current.addAll(closingLeases.values());
        }
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
                    releaseCapacity(session);
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

    private JsonNode executeWithBridge(ExecutionSession session, String executionId, String command, Duration timeout) {
        var running = commandIo.submit(() -> io.request(
                session,
                "EXEC",
                new WorkerRequests.Exec(executionId, session.commit, command, timeout.toMillis()),
                timeout.plusSeconds(5)));
        long deadline = System.nanoTime() + timeout.plusSeconds(5).toNanos();
        try {
            while (!running.isDone()) {
                if (session.stopping.get()) {
                    break;
                }
                io.authorize(session);
                if (System.nanoTime() >= deadline) {
                    throw new WorkerUnavailableException();
                }
                JsonNode polled;
                try {
                    polled = io.request(session, "BRIDGE_POLL", new WorkerRequests.BridgePoll(), Duration.ofSeconds(3));
                } catch (RuntimeException failed) {
                    if (session.stopping.get()) {
                        break;
                    }
                    throw failed;
                }
                if (running.isDone() || session.stopping.get()) {
                    break;
                }
                if (!polled.path("ok").asBoolean(false)
                        && Set.of("LEASE_EXPIRED", "BRIDGE_UNAVAILABLE", "AUTH_REVOKED")
                                .contains(polled.path("code").asString(""))) {
                    break;
                }
                requireOk(polled, session);
                JsonNode request = polled.path("bridgeRequest");
                if (request.isMissingNode() || request.isNull()) {
                    continue;
                }
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

    private CompletableFuture<Void> stop(ExecutionSession session, String reason) {
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

    private void stopAndAwait(ExecutionSession session, String reason) {
        try {
            if (session.capacityReleased) {
                return;
            }
            if (session.stopped.isCompletedExceptionally()) {
                // A new caller retries only the idempotent CLOSE, never the failed command.
                closeWorker(session, session.closeReason);
                releaseCapacity(session);
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

    /**
     * Releases confirmed worker capacity while preserving the account binding. A later command
     * can attach the same disk files under a new lease; explicit disposal removes the binding.
     */
    private synchronized void releaseCapacity(ExecutionSession session) {
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

    @EventListener
    void revoked(AuthRevocation event) {
        List<ExecutionSession> affected;
        synchronized (this) {
            affected = sessions.values().stream()
                    .filter(session -> session.key.workspace().equals(event.workspaceId())
                            && (event.apiKeyIds().contains(session.principal.subjectId())
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
                pause();
            }
            for (ExecutionSession session : affected) {
                stopAndAwait(session, "session_closed");
            }
        } catch (RuntimeException exception) {
            log.error("Revoked worker process-tree termination is unconfirmed; matching leases are no longer renewed");
            throw exception;
        }
    }

    @Override
    public void close() {
        List<ExecutionSession> remaining;
        synchronized (this) {
            closed = true;
            remaining = new ArrayList<>(sessions.values());
            remaining.addAll(closingLeases.values());
            sessions.clear();
            closingLeases.clear();
        }
        heartbeat.shutdownNow();
        remaining.forEach(session -> stop(session, "client_shutdown"));
        for (ExecutionSession session : remaining) {
            try {
                stopAndAwait(session, "client_shutdown");
            } catch (RuntimeException exception) {
                log.warn("Worker shutdown termination not acknowledged; lease renewal stopped");
            }
        }
        controls.shutdownNow();
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

    private static void pause() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new WorkerUnavailableException();
        }
    }
}
