package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.assets.ManagedAsset;
import io.github.core607.poketto.assets.MediaFileService;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthRevocation;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.content.ContentExportException;
import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.PortableContentExports;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.ExecutionUnconfirmedException;
import io.github.core607.poketto.mcp.McpSessionClosed;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.mcp.SessionReplacedException;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import tools.jackson.databind.JsonNode;

/** Maintains trusted MCP execution leases; authority exports and worker directories have separate lifetimes. */
final class IsolatedRepositoryExecutor implements RepositoryExecutor, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(IsolatedRepositoryExecutor.class);
    private final AuthService auth;
    private final RepositorySnapshotExports exports;
    private final PortableContentExports packages;
    private final WorkerClient worker;
    private final SelectedFileSaves saves;
    private final MediaFileService media;
    private final AccountCopyStore accounts;
    private final int maxSessions;
    private final Duration openTimeout;
    private final Duration closeTimeout;
    private final Map<SessionKey, Session> sessions = new LinkedHashMap<>();
    private final Map<UUID, Session> closingLeases = new LinkedHashMap<>();
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
        this.media = media;
        this.saves = saves;
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
            CopyRequest expected,
            Optional<String> requested,
            String command,
            Duration timeout,
            ExecutionCancellation cancellation) {
        var access = authorize(principal, workspace);
        validateExecution(serverSessionId, expected, requested, command, timeout);
        var owner = new AccountCopyRecord.Owner(
                principal.accountId(), workspace.value(), access.capabilities().contains(Capability.READ_PRIVATE));
        try (var held = new AccountCommand(accounts, accounts.ownerFor(owner, expected.id()), cancellation)) {
            acquireExecution(cancellation);
            try {
                Session session = accountSession(principal, workspace, expected, held, cancellation);
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

    private Session accountSession(
            AuthPrincipal principal,
            WorkspaceId workspace,
            CopyRequest expected,
            AccountCommand held,
            ExecutionCancellation cancellation) {
        SessionKey key = keyFor(held.owner());
        var record = held.record();
        requireAccountRequest(record, expected);
        WorkerClient.Hello hello = worker.hello();
        Session previous;
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
        Session idle = null;
        synchronized (this) {
            if (activeSessions() < maxSessions) {
                return;
            }
            for (Session candidate : sessions.values()) {
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

    private synchronized Session reserveAccount(
            AuthPrincipal principal, SessionKey key, WorkerClient.Hello hello, AccountCopyRecord record) {
        if (closed || activeSessions() >= maxSessions || sessions.size() >= 1024) {
            throw rejected("session_limit");
        }
        boolean full = key.scopeHash().equals(hash("full"));
        var current = new Session(
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
            Session session,
            AccountCommand held,
            Optional<String> requested,
            String command,
            Duration timeout,
            ExecutionCancellation cancellation) {
        boolean attempted = false;
        try (var registration = cancellation.onCancel(() -> stopAndAwait(session, "cancelled"))) {
            requireLive(session);
            if (session.commit != null && requested.isPresent() && !session.commit.equals(requested.get())) {
                throw new IllegalArgumentException(
                        "The account copy remains pinned; synchronize or explicitly discard it");
            }
            authorize(session);
            if (!session.ready) {
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
            UUID execution = UUID.randomUUID();
            held.begin(execution);
            session.accountRecord = held.record();
            attempted = true;
            JsonNode response = executeWithBridge(session, execution.toString(), command, timeout);
            requireOk(response, session);
            authorize(session);
            // Decode before acknowledging the journal, so malformed completion stays unconfirmed.
            result(response.path("result"), session.copyId.toString(), session.commit, held.view());
            held.complete(session.saveState.snapshot());
            session.accountRecord = held.record();
            ExecutionResult result =
                    result(response.path("result"), session.copyId.toString(), session.commit, held.view());
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

    private static CopyRetention unconfirmedView(Session session, AccountCommand held) {
        try {
            return held.view();
        } catch (RetainedCopyException unavailable) {
            var known = session.accountRecord;
            return new CopyRetention(known.expiresAt(), false, known.lastInterruptedCommand());
        }
    }

    private void containFailedCommand(Session session, RuntimeException failure) {
        try {
            stopAndAwait(session, "cancelled");
        } catch (RuntimeException closing) {
            failure.addSuppressed(closing);
            log.warn("Account command containment remains unconfirmed", closing);
        }
    }

    private AccountCopyRecord.Writer writer(Session session) {
        return new AccountCopyRecord.Writer(
                session.principal.subjectId(),
                session.hello.workerBootId(),
                worker.applicationBootId(),
                session.leaseId);
    }

    private void fenceAccount(
            AuthPrincipal principal, AccountCopyRecord record, SessionKey key, WorkerClient.Hello hello) {
        Session control;
        synchronized (this) {
            control = sessions.get(key);
            if (control == null || !control.leaseId.equals(record.writer().leaseId())) {
                control = closingLeases.get(record.writer().leaseId());
            }
            if (control == null) {
                control = new Session(
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
                var key = new SessionKey(principal.accountId(), workspace, hash(full ? "full" : "public"));
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

    private void discardDisk(UUID principalId, AccountCopyRecord record, SessionKey key, WorkerClient.Hello hello) {
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
                var key = new SessionKey(
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

    private void fenceCollectedAccount(AccountCopyRecord record, SessionKey key, WorkerClient.Hello hello) {
        Session current;
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
        authorize(principal, workspace);
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
        var access = authorize(principal, workspace);
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
        authorize(principal, workspace);
        validateArtifactRequest(serverSessionId, artifactId, offset, limit);
        Session session;
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
            Session active = session;
            try (var registration = cancellation.onCancel(() -> stopAndAwait(active, "cancelled"))) {
                requireLive(session);
                authorize(session);
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

    private Optional<ArtifactChunk> readArtifactPage(Session session, String artifactId, long offset, int limit) {
        JsonNode response = requestLive(
                session,
                "ARTIFACT_READ",
                new WorkerRequests.ArtifactRead(artifactId, offset, limit),
                Duration.ofSeconds(3));
        requireLive(session);
        authorize(session);
        String code = response.path("code").asString("");
        if (code.equals("ARTIFACT_UNAVAILABLE")) {
            return Optional.empty();
        }
        if (code.equals("INVALID_ARTIFACT_RANGE")) {
            throw new IllegalArgumentException("Invalid artifact range");
        }
        requireOk(response, session);
        var metadata = artifactMetadata(response);
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

    /** The worker's own report about one artifact; its rules live in the record that carries it. */
    private static ArtifactMetadata artifactMetadata(JsonNode value) {
        return WorkerResponses.read(value, ArtifactMetadata.class);
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

    private void initializeCopy(Session session, Optional<String> requested, AccountCommand held) {
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
            authorize(session);
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

    private void awaitInitialized(Session session, JsonNode response, long deadline) {
        while (true) {
            requireInitializationAccepted(response, session.attaching);
            requireOk(response, session);
            requireLive(session);
            String state = response.path("state").asString("");
            if (state.equals("READY")) {
                session.ready = true;
                return;
            }
            if (!state.equals("INITIALIZING") || System.nanoTime() >= deadline) {
                throw new WorkerUnavailableException();
            }
            pause();
            authorize(session);
            response = requestLive(session, "RENEW", new WorkerRequests.Renew(), Duration.ofSeconds(3));
        }
    }

    private WorkspaceAccess authorize(AuthPrincipal principal, WorkspaceId workspace) {
        if (principal == null || principal.kind() != AuthPrincipal.Kind.API_KEY) {
            throw new SecurityException("Execution requires an API key");
        }
        return auth.authorize(principal, workspace, Capability.EXECUTE_REPOSITORY);
    }

    private static SessionKey keyFor(AccountCopyRecord.Owner owner) {
        return new SessionKey(
                owner.accountId(), new WorkspaceId(owner.workspaceId()), hash(owner.fullRead() ? "full" : "public"));
    }

    private Session refreshLease(Session previous, AuthPrincipal principal) {
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
        var current = new Session(previous.key, principal, previous.fullRead, previous.copyId, UUID.randomUUID());
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

    private void attach(Session session) {
        authorize(session);
        synchronized (session) {
            requireLive(session);
            session.openAttempted = true;
            session.nextRenew = System.nanoTime()
                    + Duration.ofSeconds(session.hello.renewAfterSeconds()).toNanos();
        }
        JsonNode response = requestLive(
                session,
                "ATTACH",
                new WorkerRequests.DiskCopy(session.copyId, session.fullRead ? "full" : "public", session.commit),
                openTimeout);
        requireOk(response, session);
        if (!response.path("state").asString("").equals("READY")) {
            throw new WorkerUnavailableException();
        }
        session.ready = true;
    }

    private void authorize(Session session) {
        if (session.fullRead) {
            auth.authorize(
                    session.principal, session.key.workspace(), Capability.READ_PRIVATE, Capability.EXECUTE_REPOSITORY);
        } else if (session.publicExport != null) {
            exports.requireCurrentPublic(session.principal, session.key.workspace(), session.publicExport);
        } else {
            authorize(session.principal, session.key.workspace());
        }
    }

    private void renewDue() {
        List<Session> current;
        synchronized (this) {
            current = new ArrayList<>(sessions.values());
            current.addAll(closingLeases.values());
        }
        for (Session session : current) {
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
                        authorize(session);
                        JsonNode response =
                                requestLive(session, "RENEW", new WorkerRequests.Renew(), Duration.ofSeconds(3));
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

    private void reconcileClose(Session session) {
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

    private JsonNode executeWithBridge(Session session, String executionId, String command, Duration timeout) {
        var running = commandIo.submit(() -> requestLive(
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
                authorize(session);
                if (System.nanoTime() >= deadline) {
                    throw new WorkerUnavailableException();
                }
                JsonNode polled;
                try {
                    polled =
                            requestLive(session, "BRIDGE_POLL", new WorkerRequests.BridgePoll(), Duration.ofSeconds(3));
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
                authorize(session);
                var reply = bridgeReply(session, executionId, request);
                requireLive(session);
                authorize(session);
                requireOk(
                        requestLive(
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

    private BridgeReplies.Reply bridgeReply(Session session, String executionId, JsonNode request) {
        try {
            return bridgeOperation(session, executionId, request);
        } catch (MaterializationCapacity failure) {
            return BridgeReplies.failed(
                    "MATERIALIZE_CAPACITY",
                    "Insufficient session space. Free local space or export fewer files; existing files were preserved.");
        }
    }

    /**
     * Routes one command to the family that owns it. Each family translates the same exceptions
     * into its own failure codes -- a rejected selection is INVALID_EXPORT_SELECTION, INVALID_ARTIFACT,
     * INVALID_MEDIA_REQUEST or INVALID_SELECTION depending on who asked -- so the translation stays
     * with the family rather than being shared, and the agent inside the sandbox is told which of
     * its arguments was refused.
     */
    private BridgeReplies.Reply bridgeOperation(Session session, String executionId, JsonNode request) {
        JsonNode arguments = request.path("arguments");
        if (!arguments.isObject()) {
            throw new WorkerUnavailableException();
        }
        String operation = request.path("operation").asString("");
        // A status call takes no argument. One that carries any is not a status call at all, and
        // falls through to the unknown-operation reply rather than being answered with a guess.
        if (operation.equals("status") && arguments.isEmpty()) {
            return status(session);
        }
        if (operation.equals("export")) {
            return exportCommand(session, executionId, arguments);
        }
        if (operation.equals("edit") || operation.equals("create")) {
            return localTextCommand(session, executionId, operation, arguments);
        }
        if (operation.equals("artifact_create") || operation.equals("artifact_remove")) {
            return artifactCommand(session, executionId, operation, arguments);
        }
        if (operation.equals("media_fetch")
                || operation.equals("media_import")
                || operation.equals("media_link")
                || operation.equals("media_list")) {
            return mediaCommand(session, executionId, operation, arguments);
        }
        if (operation.equals("save")
                || operation.equals("recover")
                || operation.equals("sync")
                || operation.equals("move")) {
            return writeCommand(session, executionId, operation, arguments);
        }
        return BridgeReplies.failed("OPERATION_UNAVAILABLE");
    }

    /** Where this session's writes stand, as the agent needs to see them before deciding what to do. */
    private static BridgeReplies.Reply status(Session session) {
        return BridgeReplies.succeeded(new BridgeReplies.Status(
                session.copyId.toString(),
                session.fullRead ? "full" : "public",
                session.saveState.baseCommit,
                session.saveState.uncertain
                        || (session.saveState.move != null && session.saveState.move.result == null),
                session.saveState.move != null,
                session.saveState.move == null
                        ? new BridgeReplies.Absent()
                        : SessionMoves.movePending(session.saveState.move),
                session.saveState.lastSave,
                session.saveState.lastImport));
    }

    private BridgeReplies.Reply exportCommand(Session session, String executionId, JsonNode arguments) {
        try {
            return exportPackage(session, executionId, arguments);
        } catch (AuthException denied) {
            return BridgeReplies.failed("ACCESS_DENIED");
        } catch (IllegalArgumentException invalid) {
            return BridgeReplies.failed("INVALID_EXPORT_SELECTION");
        } catch (ContentExportException unavailable) {
            return BridgeReplies.failed("EXPORT_" + unavailable.reason().name());
        } catch (ContentRepositoryException | AssetStorageException unavailable) {
            return BridgeReplies.failed("EXPORT_UNAVAILABLE");
        }
    }

    /**
     * Creates or releases one retained artifact. The worker's own refusals pass through unchanged,
     * because the agent distinguishes a missing artifact from a full store from a bad request.
     */
    private BridgeReplies.Reply artifactCommand(
            Session session, String executionId, String operation, JsonNode arguments) {
        boolean create = operation.equals("artifact_create");
        try {
            WorkerRequests.Data data;
            if (create) {
                var selected = BridgeArguments.artifactCreate(arguments);
                data = new WorkerRequests.ArtifactCreate(executionId, selected.path(), selected.mediaType());
            } else {
                data = new WorkerRequests.ArtifactRemove(
                        BridgeArguments.artifactRemove(arguments).artifactId());
            }
            authorize(session);
            JsonNode result =
                    requestLive(session, create ? "ARTIFACT_CREATE" : "ARTIFACT_REMOVE", data, Duration.ofSeconds(10));
            authorize(session);
            String code = result.path("code").asString("");
            if (Set.of("ARTIFACT_UNAVAILABLE", "ARTIFACT_CAPACITY", "INVALID_ARTIFACT")
                    .contains(code)) {
                return BridgeReplies.failed(code);
            }
            requireOk(result, session);
            return create ? BridgeReplies.artifact(artifactMetadata(result.path("artifact"))) : BridgeReplies.removed();
        } catch (IllegalArgumentException invalid) {
            return BridgeReplies.failed("INVALID_ARTIFACT");
        }
    }

    private BridgeReplies.Reply mediaCommand(
            Session session, String executionId, String operation, JsonNode arguments) {
        try {
            return switch (operation) {
                case "media_list" -> listMedia(session, executionId, arguments);
                case "media_link" -> linkMedia(session, executionId, arguments);
                case "media_fetch" -> fetchMedia(session, executionId, arguments);
                default -> importMedia(session, executionId, arguments);
            };
        } catch (IllegalArgumentException invalid) {
            return BridgeReplies.failedBecause("INVALID_MEDIA_REQUEST", InvalidSelectionException.reason(invalid));
        } catch (AuthException denied) {
            return BridgeReplies.failed("ACCESS_DENIED");
        } catch (AssetStorageException unavailable) {
            return BridgeReplies.failedBecause(
                    "MEDIA_UNAVAILABLE", unavailable.reason().name());
        } catch (ContentRepositoryException unavailable) {
            return BridgeReplies.failed("MEDIA_UNAVAILABLE");
        }
    }

    /**
     * The four commands that reconcile the sandbox with the repository. Three of them write to it;
     * sync only reads, which is why the capability it needs is the lesser one. All four share the
     * full-read scope check and the refusal to start while an earlier write is unresolved, so those
     * stay here and only the command itself differs.
     */
    private BridgeReplies.Reply writeCommand(
            Session session, String executionId, String operation, JsonNode arguments) {
        if (!session.fullRead) {
            return BridgeReplies.failed("READ_ONLY_SCOPE");
        }
        try {
            authorize(session);
            // Recovery is the one command allowed while a write is unresolved: it exists to resolve
            // one. The guards below would otherwise refuse it and leave the session stuck.
            if (operation.equals("recover")) {
                return recoverCommand(session, executionId, arguments);
            }
            if (session.saveState.move != null) {
                return SessionMoves.pendingResult(session.saveState.move, "RECOVER_MOVE_FIRST");
            }
            if (session.saveState.uncertain) {
                return BridgeReplies.failed("WRITE_OUTCOME_UNKNOWN");
            }
            return switch (operation) {
                case "move" -> moveCommand(session, executionId, arguments);
                case "sync" -> syncCommand(session, executionId, arguments);
                default -> saveCommand(session, executionId, arguments);
            };
        } catch (IllegalArgumentException invalid) {
            return BridgeReplies.failedBecause("INVALID_SELECTION", InvalidSelectionException.reason(invalid));
        } catch (AuthException denied) {
            return BridgeReplies.failed("ACCESS_DENIED");
        } catch (ContentRepositoryException unavailable) {
            return BridgeReplies.failed("REPOSITORY_UNAVAILABLE");
        }
    }

    /**
     * Finishes whichever write was left unresolved. A move that the remote acknowledged still has
     * to be installed locally, and {@code --skip-local} releases that installation instead, which
     * is only meaningful once the remote outcome is known.
     */
    private BridgeReplies.Reply recoverCommand(Session session, String executionId, JsonNode arguments) {
        boolean skipLocal = BridgeArguments.recoverSkipsLocal(arguments);
        if (session.saveState.move != null) {
            var recovered = saves.moves().recover(session.principal, session.key.workspace(), session.saveState);
            if (session.saveState.move == null || session.saveState.move.result == null) {
                return recovered;
            }
            if (skipLocal) {
                return saves.moves().skipLocal(session.saveState);
            }
            return moveFiles(session, executionId, session.saveState.move, true);
        }
        if (skipLocal) {
            throw new IllegalArgumentException("no confirmed move to skip");
        }
        return saves.recover(session.principal, session.key.workspace(), session.saveState);
    }

    private BridgeReplies.Reply moveCommand(Session session, String executionId, JsonNode arguments) {
        var selected = BridgeArguments.move(arguments);
        var pending = saves.moves()
                .prepare(
                        session.principal,
                        session.key.workspace(),
                        session.saveState,
                        selected.source(),
                        selected.destination(),
                        captureOptional(session, executionId, RepositoryMediaIndex.PATH));
        return moveFiles(session, executionId, pending, false);
    }

    /**
     * Merges one file against its own baseline. The capture is optional because the agent may have
     * deleted the file, and an absent path is a deletion to reconcile rather than a missing input.
     */
    private BridgeReplies.Reply syncCommand(Session session, String executionId, JsonNode arguments) {
        String path = BridgeArguments.sync(arguments).path();
        JsonNode manifest = requestLive(
                session, "CAPTURE_OPTIONAL", new WorkerRequests.CapturePath(executionId, path), Duration.ofSeconds(5));
        if (manifest.path("code").asString("").equals("CAPTURE_REJECTED")) {
            throw InvalidSelectionException.capture(manifest.path("reason").asString(""));
        }
        requireOk(manifest, session);
        List<String> absent = WorkerResponses.read(manifest, WorkerResponses.CaptureManifest.class)
                .absent();
        if (!absent.isEmpty() && !absent.equals(List.of(path))) {
            throw new WorkerUnavailableException();
        }
        var captured =
                readCapture(session, executionId, manifest, absent.isEmpty() ? List.of(path) : List.of(), List.of());
        var plan = saves.prepareSync(
                session.principal,
                session.key.workspace(),
                session.saveState,
                path,
                Optional.ofNullable(captured.get(path)));
        return synchronizeFile(session, executionId, plan);
    }

    private BridgeReplies.Reply saveCommand(Session session, String executionId, JsonNode arguments) {
        var selection = BridgeArguments.save(arguments);
        List<String> writes = selection.writes();
        List<String> deletes = selection.deletes();
        var captured = capture(session, executionId, writes, deletes);
        requireLive(session);
        authorize(session);
        return saves.save(session.principal, session.key.workspace(), session.saveState, captured, deletes);
    }

    private BridgeReplies.Reply moveFiles(
            Session session, String executionId, SessionMoves.Pending pending, boolean recovery) {
        byte[] payload = pending.payload;
        JsonNode begun = requestLive(
                session,
                "MOVE_BEGIN",
                new WorkerRequests.MoveBegin(
                        executionId,
                        payload.length,
                        DocumentRevision.sha256(payload).value().substring(7)),
                Duration.ofSeconds(3));
        if (begun.path("code").asString("").equals("MOVE_REJECTED")) {
            if (recovery) {
                return SessionMoves.pendingResult(pending, "LOCAL_MOVE_PENDING");
            }
            return BridgeReplies.failed("LOCAL_MOVE_REJECTED");
        }
        requireOk(begun, session);
        String transfer =
                WorkerResponses.read(begun, WorkerResponses.Transfer.class).transferId();
        var reference = new WorkerRequests.Transfer(executionId, transfer);
        try {
            for (int offset = 0; offset < payload.length; offset += 65536) {
                authorize(session);
                int end = Math.min(offset + 65536, payload.length);
                JsonNode chunk = requestLive(
                        session,
                        "MOVE_CHUNK",
                        new WorkerRequests.TransferChunk(
                                executionId,
                                transfer,
                                offset,
                                Base64.getEncoder().encodeToString(Arrays.copyOfRange(payload, offset, end))),
                        Duration.ofSeconds(3));
                if (chunk.path("code").asString("").equals("MOVE_REJECTED")) {
                    if (recovery) {
                        return SessionMoves.pendingResult(pending, "LOCAL_MOVE_PENDING");
                    }
                    return BridgeReplies.failed("LOCAL_MOVE_REJECTED");
                }
                requireOk(chunk, session);
                if (WorkerResponses.read(chunk, WorkerResponses.TransferProgress.class)
                                .receivedBytes()
                        != end) {
                    throw new WorkerUnavailableException();
                }
            }
            if (!recovery) {
                JsonNode checked = requestLive(session, "MOVE_CHECK", reference, Duration.ofSeconds(15));
                if (checked.path("code").asString("").equals("MOVE_REJECTED")) {
                    return BridgeReplies.failed("LOCAL_MOVE_REJECTED");
                }
                requireOk(checked, session);
                if (!WorkerResponses.read(checked, WorkerResponses.MovePreflight.class)
                        .ready()) {
                    throw new WorkerUnavailableException();
                }
                authorize(session);
                var committed =
                        saves.moves().commit(session.principal, session.key.workspace(), session.saveState, pending);
                pending = session.saveState.move;
                if (pending == null || pending.result == null) {
                    return committed;
                }
            }
            authorize(session);
            JsonNode installed;
            try {
                installed = requestLive(session, "MOVE_COMMIT", reference, Duration.ofSeconds(15));
            } catch (WorkerUnavailableException uncertainInstallation) {
                // Git is acknowledged. Keep the plan so a live worker can reconcile its receipt.
                return SessionMoves.pendingResult(pending, "LOCAL_MOVE_PENDING");
            }
            if (installed.path("code").asString("").equals("MOVE_REJECTED")) {
                return SessionMoves.pendingResult(pending, "LOCAL_MOVE_CONFLICT");
            }
            requireOk(installed, session);
            if (WorkerResponses.read(installed, WorkerResponses.MoveInstallation.class)
                            .installed()
                            .changedPaths()
                    != pending.paths.size()) {
                throw new WorkerUnavailableException();
            }
            return saves.moves().installed(session.saveState);
        } finally {
            try {
                requestLive(session, "MOVE_ABORT", reference, Duration.ofSeconds(3));
            } catch (RuntimeException cleanupFailure) {
                log.warn(
                        "Worker move staging cleanup was not acknowledged; command cleanup will release its slot",
                        cleanupFailure);
            }
        }
    }

    private Map<String, String> capture(
            Session session, String executionId, List<String> writes, List<String> deletes) {
        JsonNode manifest = requestLive(
                session,
                "CAPTURE_BEGIN",
                new WorkerRequests.CaptureBegin(executionId, writes, deletes),
                Duration.ofSeconds(5));
        return readCapture(session, executionId, manifest, writes, deletes);
    }

    private Map<String, String> readCapture(
            Session session, String executionId, JsonNode manifest, List<String> writes, List<String> deletes) {
        if (manifest.path("code").asString("").equals("CAPTURE_REJECTED")) {
            throw InvalidSelectionException.capture(manifest.path("reason").asString(""));
        }
        requireOk(manifest, session);
        var captured = WorkerResponses.read(manifest, WorkerResponses.CaptureManifest.class);
        String captureId = captured.captureId();
        var reference = new WorkerRequests.CaptureRelease(executionId, captureId);
        try {
            // The manifest is well formed by construction; these compare it against this request.
            if (captured.writes().size() != writes.size() || !captured.deletes().equals(deletes)) {
                throw new WorkerUnavailableException();
            }
            var result = new LinkedHashMap<String, String>();
            for (int index = 0; index < captured.writes().size(); index++) {
                var file = captured.writes().get(index);
                String path = file.path();
                long size = file.bytes();
                if (!path.equals(writes.get(index)) || result.containsKey(path)) {
                    throw new WorkerUnavailableException();
                }
                var bytes = new ByteArrayOutputStream((int) size);
                while (bytes.size() < size) {
                    authorize(session);
                    int limit = (int) Math.min(65536, size - bytes.size());
                    JsonNode chunk = requestLive(
                            session,
                            "CAPTURE_READ",
                            new WorkerRequests.CaptureRead(executionId, captureId, index, bytes.size(), limit),
                            Duration.ofSeconds(3));
                    requireOk(chunk, session);
                    var page = WorkerResponses.read(chunk, WorkerResponses.CaptureChunk.class);
                    if (!captureId.equals(page.captureId()) || page.index() != index || page.offset() != bytes.size()) {
                        throw new WorkerUnavailableException();
                    }
                    byte[] block = page.decoded();
                    if (block.length != limit) {
                        throw new WorkerUnavailableException();
                    }
                    bytes.writeBytes(block);
                }
                byte[] content = bytes.toByteArray();
                try {
                    if (!HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(content))
                            .equals(file.sha256())) {
                        throw new WorkerUnavailableException();
                    }
                    result.put(
                            path,
                            StandardCharsets.UTF_8
                                    .newDecoder()
                                    .onMalformedInput(CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                                    .decode(ByteBuffer.wrap(content))
                                    .toString());
                } catch (NoSuchAlgorithmException | CharacterCodingException invalid) {
                    throw new WorkerUnavailableException(invalid);
                }
            }
            return result;
        } finally {
            requireOk(requestLive(session, "CAPTURE_RELEASE", reference, Duration.ofSeconds(3)), session);
        }
    }

    private BridgeReplies.Reply localTextCommand(
            Session session, String executionId, String operation, JsonNode arguments) {
        try {
            String path;
            String replacement;
            Optional<String> original;
            if (operation.equals("edit")) {
                var edit = BridgeArguments.edit(arguments);
                path = edit.path();
                original = captureOptional(session, executionId, path);
                if (original.isEmpty()) {
                    return BridgeReplies.failedBecause("EDIT_REJECTED", "NOT_FOUND");
                }
                String text = original.orElseThrow();
                int start = text.indexOf(edit.oldText());
                if (start < 0) {
                    return BridgeReplies.failedBecause("EDIT_REJECTED", "OLD_TEXT_NOT_FOUND");
                }
                if (text.indexOf(edit.oldText(), start + 1) >= 0) {
                    return BridgeReplies.failedBecause("EDIT_REJECTED", "AMBIGUOUS_MATCH");
                }
                replacement = text.substring(0, start)
                        + edit.newText()
                        + text.substring(start + edit.oldText().length());
            } else {
                var create = BridgeArguments.create(arguments);
                path = create.path();
                replacement = create.text();
                original = captureOptional(session, executionId, path);
                if (original.isPresent()) {
                    return BridgeReplies.failedBecause("EDIT_REJECTED", "ALREADY_EXISTS");
                }
            }
            if (replacement.indexOf('\0') >= 0
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(replacement)) {
                return BridgeReplies.failedBecause("EDIT_REJECTED", "INVALID_TEXT");
            }
            return installLocalText(session, executionId, path, original, replacement);
        } catch (InvalidSelectionException invalid) {
            return BridgeReplies.failedBecause("EDIT_REJECTED", InvalidSelectionException.reason(invalid));
        } catch (IllegalArgumentException invalid) {
            return BridgeReplies.failedBecause("EDIT_REJECTED", "INVALID_INPUT");
        }
    }

    private BridgeReplies.Reply installLocalText(
            Session session, String executionId, String path, Optional<String> original, String replacement) {
        byte[] content = replacement.getBytes(StandardCharsets.UTF_8);
        if (content.length > ContentLimits.MAX_DOCUMENT_BYTES) {
            return BridgeReplies.failedBecause("EDIT_REJECTED", "TOO_LARGE");
        }
        boolean installed = materialize(
                session,
                executionId,
                path,
                content.length,
                hash(replacement),
                original.map(IsolatedRepositoryExecutor::hash).orElse(null),
                false,
                false,
                output -> output.write(content));
        return installed
                ? BridgeReplies.succeeded(new BridgeReplies.LocalEditResult(path, false))
                : BridgeReplies.failedBecause("EDIT_REJECTED", "LOCAL_FILE_CHANGED");
    }

    private BridgeReplies.Reply synchronizeFile(Session session, String executionId, SelectedFileSaves.SyncPlan plan) {
        byte[] content = plan.content().orElse("").getBytes(StandardCharsets.UTF_8);
        boolean installed = materialize(
                session,
                executionId,
                plan.path(),
                content.length,
                hash(plan.content().orElse("")),
                plan.expectedLocalSha256().orElse(null),
                plan.content().isEmpty(),
                false,
                output -> output.write(content));
        if (!installed) {
            return BridgeReplies.failed("LOCAL_UPDATE_REJECTED");
        }
        saves.acknowledgeSync(session.saveState, plan);
        return BridgeReplies.outcome(
                !plan.conflicted(),
                plan.conflicted() ? "MERGE_CONFLICT" : "SYNCHRONIZED",
                new BridgeReplies.SyncResult(plan.path(), plan.remoteCommit(), false, plan.conflicted()));
    }

    private BridgeReplies.Reply listMedia(Session session, String executionId, JsonNode arguments) {
        var query = MediaListing.Query.parse(arguments);
        Map<String, RepositoryMediaIndex.Media> files;
        String version, source;
        if (session.fullRead) {
            if (query.commit() == null) {
                var index = captureOptional(session, executionId, RepositoryMediaIndex.PATH);
                source = "worktree";
                files = index.map(value -> RepositoryMediaIndex.parse(value.getBytes(StandardCharsets.UTF_8)))
                        .orElseGet(RepositoryMediaIndex::empty)
                        .files();
                version = hash(index.orElse(""));
            } else {
                var catalog =
                        media.privateCatalog(session.principal, session.key.workspace(), Optional.of(query.commit()));
                files = catalog.index().files();
                source = "repository";
                version = hash(catalog.commit());
            }
        } else {
            if (query.commit() != null) {
                throw new IllegalArgumentException("public media uses only its current projection");
            }
            files = new LinkedHashMap<>();
            session.publicExport.media().forEach((path, media) -> files.put(path, media.original()));
            source = "public-projection";
            version = session.publicExport.projectionSha256();
        }
        authorize(session);
        return MediaListing.page(files, query, version, source);
    }

    private BridgeReplies.Reply fetchMedia(Session session, String executionId, JsonNode arguments) {
        var selected = BridgeArguments.mediaFetch(arguments);
        String path = selected.path();
        String destination = selected.output() == null ? path : selected.output();
        Optional<String> requested = Optional.ofNullable(selected.commit());
        MediaFileService.Download download;
        String commit = null;
        String indexSource;
        if (session.fullRead) {
            if (requested.isPresent()) {
                commit = requested.orElseThrow();
                if (!commit.matches("[0-9a-f]{40}")) {
                    throw new IllegalArgumentException("a pinned commit must be 40 lowercase hex characters");
                }
                download = media.privateDownload(session.principal, session.key.workspace(), Optional.of(commit), path);
                indexSource = "repository";
            } else {
                var source = captureOptional(session, executionId, RepositoryMediaIndex.PATH);
                var index = source.map(value -> RepositoryMediaIndex.parse(value.getBytes(StandardCharsets.UTF_8)))
                        .orElseGet(RepositoryMediaIndex::empty);
                download = media.privateOriginal(
                        session.principal,
                        session.key.workspace(),
                        path,
                        index.files().get(path));
                indexSource = "worktree";
            }
        } else {
            if (requested.isPresent()) {
                throw new IllegalArgumentException("public media uses only its current projection");
            }
            var approved = session.publicExport.media().get(path);
            if (approved == null) {
                return BridgeReplies.failed("MEDIA_UNAVAILABLE");
            }
            download = media.memberProjectionDownload(
                    session.principal,
                    session.key.workspace(),
                    approved.route(),
                    session.publicExport.sourcePaths().get(path));
            var expected = approved.original();
            var asset = download.asset();
            if (!asset.reference().assetId().equals(expected.assetId())
                    || !asset.reference().revision().equals(expected.revision())
                    || asset.size() != expected.size()
                    || !asset.mediaType().equals(expected.mediaType())) {
                return BridgeReplies.failed("MEDIA_UNAVAILABLE");
            }
            commit = session.commit;
            indexSource = "public-projection";
        }
        var asset = download.asset();
        boolean installed = materialize(
                session,
                executionId,
                destination,
                asset.size(),
                asset.reference().revision(),
                null,
                false,
                true,
                download::writeTo);
        if (!installed) {
            return BridgeReplies.failed(
                    "LOCAL_FILE_EXISTS", "A different local file exists; keep it or choose another --output path.");
        }
        return BridgeReplies.succeeded(new BridgeReplies.FetchResult(
                destination, path, indexSource, commit, asset.reference().revision(), asset.mediaType(), asset.size()));
    }

    private Optional<String> captureOptional(Session session, String executionId, String path) {
        JsonNode manifest = requestLive(
                session, "CAPTURE_OPTIONAL", new WorkerRequests.CapturePath(executionId, path), Duration.ofSeconds(5));
        if (manifest.path("code").asString("").equals("CAPTURE_REJECTED")) {
            throw InvalidSelectionException.capture(manifest.path("reason").asString(""));
        }
        requireOk(manifest, session);
        List<String> absent = WorkerResponses.read(manifest, WorkerResponses.CaptureManifest.class)
                .absent();
        if (!absent.isEmpty() && !absent.equals(List.of(path))) {
            throw new WorkerUnavailableException();
        }
        return Optional.ofNullable(
                readCapture(session, executionId, manifest, absent.isEmpty() ? List.of(path) : List.of(), List.of())
                        .get(path));
    }

    private BridgeReplies.Reply importMedia(Session session, String executionId, JsonNode arguments) {
        if (!session.fullRead) {
            return BridgeReplies.failed("READ_ONLY_SCOPE");
        }
        auth.authorize(session.principal, session.key.workspace(), Capability.WRITE_PRIVATE);
        var selected = BridgeArguments.mediaImport(arguments);
        String file = selected.file(), path = selected.path();
        String mediaType = selected.mediaType(), key = selected.key();
        boolean replace = selected.replace();
        LocalMediaIndex local = localMediaIndex(session, executionId);
        if (local == null) {
            return missingMediaIndex();
        }
        RepositoryMediaIndex index = local.index();
        if (!availableMediaPath(session, local, path, mediaType)) {
            return BridgeReplies.failed("MEDIA_PATH_COLLIDES_WITH_GIT");
        }
        JsonNode manifest = requestLive(
                session, "CAPTURE_BINARY", new WorkerRequests.CapturePath(executionId, file), Duration.ofSeconds(8));
        if (manifest.path("code").asString("").equals("CAPTURE_REJECTED")) {
            throw InvalidSelectionException.capture(manifest.path("reason").asString(""));
        }
        requireOk(manifest, session);
        var captured = WorkerResponses.read(manifest, WorkerResponses.BinaryCaptureManifest.class);
        String captureId = captured.captureId();
        var reference = new WorkerRequests.CaptureRelease(executionId, captureId);
        ManagedAsset asset;
        try {
            // One file was selected, so exactly that file must come back.
            if (captured.writes().size() != 1
                    || !captured.writes().getFirst().path().equals(file)) {
                throw new WorkerUnavailableException();
            }
            long size = captured.writes().getFirst().bytes();
            String digest = captured.writes().getFirst().sha256();
            var previous = index.files().get(path);
            if (!replace
                    && previous != null
                    && (previous.size() != size
                            || !previous.revision().equals(digest)
                            || !previous.mediaType().equals(mediaType))) {
                return BridgeReplies.failed("MEDIA_PATH_EXISTS");
            }
            try (var input = new CapturedBinaryInput(size, digest, (offset, limit) -> {
                authorize(session);
                JsonNode chunk = requestLive(
                        session,
                        "CAPTURE_READ",
                        new WorkerRequests.CaptureRead(executionId, captureId, 0, offset, limit),
                        Duration.ofSeconds(3));
                requireOk(chunk, session);
                var page = WorkerResponses.read(chunk, WorkerResponses.CaptureChunk.class);
                if (!captureId.equals(page.captureId()) || page.index() != 0 || page.offset() != offset) {
                    throw new WorkerUnavailableException();
                }
                return page.decoded();
            })) {
                asset = media.upload(session.principal, session.key.workspace(), key, mediaType, input);
                session.saveState.acknowledgeImport(importReceipt(path, asset, false));
                if (!input.verified()
                        || asset.size() != size
                        || !asset.reference().revision().equals(digest)
                        || !asset.mediaType().equals(mediaType)) {
                    throw new WorkerUnavailableException();
                }
            }
        } finally {
            try {
                requestLive(session, "CAPTURE_RELEASE", reference, Duration.ofSeconds(3));
            } catch (RuntimeException cleanupFailure) {
                log.warn(
                        "Binary capture release was not acknowledged; command cleanup will release its staging file",
                        cleanupFailure);
            }
        }
        return indexMedia(session, executionId, path, asset, local, replace);
    }

    private BridgeReplies.Reply linkMedia(Session session, String executionId, JsonNode arguments) {
        if (!session.fullRead) {
            return BridgeReplies.failed("READ_ONLY_SCOPE");
        }
        auth.authorize(session.principal, session.key.workspace(), Capability.WRITE_PRIVATE);
        BridgeArguments.MediaLink selected = BridgeArguments.mediaLink(arguments);
        LocalMediaIndex local = localMediaIndex(session, executionId);
        if (local == null) {
            return missingMediaIndex();
        }
        ManagedAsset asset = media.describeOriginal(session.principal, session.key.workspace(), selected.reference());
        if (!availableMediaPath(session, local, selected.path(), asset.mediaType())) {
            return BridgeReplies.failed("MEDIA_PATH_COLLIDES_WITH_GIT");
        }
        session.saveState.acknowledgeImport(importReceipt(selected.path(), asset, false));
        return indexMedia(session, executionId, selected.path(), asset, local, selected.replace());
    }

    private record LocalMediaIndex(Optional<String> source, RepositoryMediaIndex index) {}

    private LocalMediaIndex localMediaIndex(Session session, String executionId) {
        Optional<String> source = captureOptional(session, executionId, RepositoryMediaIndex.PATH);
        if (source.isEmpty()
                && !saves.baselineFile(
                                session.principal,
                                session.key.workspace(),
                                session.saveState,
                                RepositoryMediaIndex.PATH)
                        .expectedAbsence()) {
            return null;
        }
        return new LocalMediaIndex(
                source,
                source.map(value -> RepositoryMediaIndex.parse(value.getBytes(StandardCharsets.UTF_8)))
                        .orElseGet(RepositoryMediaIndex::empty));
    }

    private static BridgeReplies.Reply missingMediaIndex() {
        return BridgeReplies.failed(
                "INDEX_MISSING",
                "Restore or intentionally recreate the local media index before importing or linking.");
    }

    private boolean availableMediaPath(Session session, LocalMediaIndex local, String path, String mediaType) {
        var entries = new LinkedHashMap<>(local.index().files());
        entries.put(path, new RepositoryMediaIndex.Media(new UUID(0, 0), "0".repeat(64), mediaType, 1));
        new RepositoryMediaIndex(
                entries); // Validate the entire logical namespace before changing originals or the index.
        RepositoryFile existingGit =
                saves.baselineFile(session.principal, session.key.workspace(), session.saveState, path);
        return existingGit.expectedAbsence()
                || existingGit.diagnostics().stream()
                        .anyMatch(value -> value.code().equals("MANAGED_MEDIA"));
    }

    private BridgeReplies.Reply indexMedia(
            Session session,
            String executionId,
            String path,
            ManagedAsset asset,
            LocalMediaIndex local,
            boolean replace) {
        auth.authorize(session.principal, session.key.workspace(), Capability.WRITE_PRIVATE);
        Optional<String> source = local.source();
        RepositoryMediaIndex index = local.index();
        var entries = new LinkedHashMap<>(index.files());
        var entry = new RepositoryMediaIndex.Media(
                asset.reference().assetId(), asset.reference().revision(), asset.mediaType(), asset.size());
        var previous = index.files().get(path);
        if (previous != null && !previous.equals(entry) && !replace) {
            return BridgeReplies.failedWith("MEDIA_PATH_EXISTS", session.saveState.lastImport);
        }
        entries.put(path, entry);
        byte[] changed = entry.equals(previous)
                ? source.orElseThrow().getBytes(StandardCharsets.UTF_8)
                : new RepositoryMediaIndex(entries).encode();
        String text = new String(changed, StandardCharsets.UTF_8);
        try {
            if (!materialize(
                    session,
                    executionId,
                    RepositoryMediaIndex.PATH,
                    changed.length,
                    hash(text),
                    source.map(IsolatedRepositoryExecutor::hash).orElse(null),
                    false,
                    false,
                    output -> output.write(changed))) {
                return BridgeReplies.failedWith("INDEX_CHANGED", session.saveState.lastImport);
            }
        } catch (MaterializationCapacity capacity) {
            return BridgeReplies.failedWith(
                    "MATERIALIZE_CAPACITY",
                    session.saveState.lastImport,
                    "Original stored; local index was not updated. Free session space and retry the same import or link.");
        }
        session.saveState.acknowledgeImport(importReceipt(path, asset, true));
        return BridgeReplies.succeeded(session.saveState.lastImport);
    }

    private static BridgeReplies.ImportReceipt importReceipt(String path, ManagedAsset asset, boolean indexed) {
        return new BridgeReplies.ImportReceipt(
                path,
                asset.reference().assetId().toString(),
                asset.reference().revision(),
                asset.mediaType(),
                asset.size(),
                true,
                indexed,
                false);
    }

    @FunctionalInterface
    private interface FileSource {
        void writeTo(OutputStream output) throws IOException;
    }

    private BridgeReplies.Reply exportPackage(Session session, String executionId, JsonNode arguments) {
        var requested = BridgeArguments.export(arguments);
        List<String> selections = requested.paths();
        String output = requested.output();
        synchronized (session) {
            requireLive(session);
        }
        authorize(session);
        var selected = SessionExportSelection.resolve(selections, session.fullRead ? null : session.publicExport);
        boolean publicOnly = !session.fullRead || requested.publicOnly();
        var client = Optional.of(session.key.scopeHash());
        try {
            var receipt = packages.create(session.principal, session.key.workspace(), selected, publicOnly, client);
            synchronized (session) {
                requireLive(session);
            }
            authorize(session);
            if (!materialize(
                    session,
                    executionId,
                    output,
                    receipt.bytes(),
                    receipt.sha256(),
                    null,
                    false,
                    true,
                    sink -> packages.copyTo(
                            session.principal, session.key.workspace(), receipt.handle(), client, sink))) {
                return BridgeReplies.failed(
                        "LOCAL_FILE_CHANGED",
                        "The output path is unavailable or contains different local bytes. Keep it or choose another --output.");
            }
            return BridgeReplies.succeeded(new BridgeReplies.ExportResult(
                    output, receipt.bytes(), receipt.sha256(), publicOnly ? "public" : "private", false));
        } finally {
            // Covers a close event that raced before create registered its build. No package handle
            // is exposed to the sandbox; every command owns only its materialized local result.
            packages.closeClient(session.principal, session.key.workspace(), session.key.scopeHash());
        }
    }

    private boolean materialize(
            Session session,
            String executionId,
            String path,
            long size,
            String digest,
            String expected,
            boolean delete,
            boolean allowIdentical,
            FileSource source) {
        var metadata =
                new WorkerRequests.MaterializeBegin(executionId, path, size, digest, expected, delete, allowIdentical);
        JsonNode begun = requestLive(session, "MATERIALIZE_BEGIN", metadata, Duration.ofSeconds(3));
        checkMaterialization(begun);
        if (begun.path("code").asString("").equals("MATERIALIZE_REJECTED")) {
            throw new IllegalArgumentException("the worker refused to stage the outgoing file");
        }
        requireOk(begun, session);
        String transferId =
                WorkerResponses.read(begun, WorkerResponses.Transfer.class).transferId();
        var reference = new WorkerRequests.Transfer(executionId, transferId);
        try {
            var sink = new OutputStream() {
                long sent;

                @Override
                public void write(int value) {
                    write(new byte[] {(byte) value}, 0, 1);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) {
                    Objects.checkFromIndexSize(offset, length, bytes.length);
                    if (length > size - sent) {
                        throw new IllegalArgumentException("media source exceeds its declared size");
                    }
                    while (length > 0) {
                        authorize(session);
                        int count = Math.min(65536, length);
                        JsonNode chunk = requestLive(
                                session,
                                "MATERIALIZE_CHUNK",
                                new WorkerRequests.TransferChunk(
                                        executionId,
                                        transferId,
                                        sent,
                                        Base64.getEncoder()
                                                .encodeToString(Arrays.copyOfRange(bytes, offset, offset + count))),
                                Duration.ofSeconds(3));
                        checkMaterialization(chunk);
                        requireOk(chunk, session);
                        sent += count;
                        if (WorkerResponses.read(chunk, WorkerResponses.TransferProgress.class)
                                        .receivedBytes()
                                != sent) {
                            throw new WorkerUnavailableException();
                        }
                        offset += count;
                        length -= count;
                    }
                }
            };
            var output = new BufferedOutputStream(sink, 65536);
            // Flush only after the source succeeds. Closing on failure could replay a buffered chunk.
            source.writeTo(output);
            output.flush();
            if (sink.sent != size) {
                throw new IllegalArgumentException("media source is incomplete");
            }
            authorize(session);
            JsonNode committed = requestLive(session, "MATERIALIZE_COMMIT", reference, Duration.ofSeconds(5));
            if (committed.path("code").asString("").equals("MATERIALIZE_REJECTED")) {
                return false;
            }
            checkMaterialization(committed);
            requireOk(committed, session);
            var installed = WorkerResponses.read(committed, WorkerResponses.Materialization.class)
                    .installed();
            // A deletion is acknowledged by an explicit null digest. An absent key is a malformed
            // answer and must not be read as a file that was erased.
            JsonNode reported = committed.path("installed").path("sha256");
            if (!installed.path().equals(path) || (delete ? !reported.isNull() : !digest.equals(installed.sha256()))) {
                throw new WorkerUnavailableException();
            }
            return true;
        } catch (IOException failure) {
            throw new WorkerUnavailableException(failure);
        } finally {
            try {
                requestLive(session, "MATERIALIZE_ABORT", reference, Duration.ofSeconds(3));
            } catch (RuntimeException cleanupFailure) {
                log.warn(
                        "Worker transfer cleanup was not acknowledged; command cleanup will release its slot",
                        cleanupFailure);
            }
        }
    }

    private JsonNode requestLive(Session session, String operation, WorkerRequests.Data data, Duration timeout) {
        WorkerClient.PreparedRequest request;
        synchronized (session) {
            requireLive(session);
            request = worker.prepare(session.hello, session.identity(), operation, data);
        }
        return worker.send(request, timeout);
    }

    private CompletableFuture<Void> stop(Session session, String reason) {
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

    private void stopAndAwait(Session session, String reason) {
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
    private synchronized void releaseCapacity(Session session) {
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

    private void closeWorker(Session session, String reason) {
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

    /** Transport closure does not release the account's working copy or execution authority. */
    @EventListener
    void closed(McpSessionClosed event) {
        // Explicit cancellation, revocation, disposal and expiry own their separate lifecycle actions.
    }

    @EventListener
    void revoked(AuthRevocation event) {
        List<Session> affected;
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
            for (Session session : affected) {
                stopAndAwait(session, "session_closed");
            }
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
            remaining.addAll(closingLeases.values());
            sessions.clear();
            closingLeases.clear();
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
        commandIo.shutdownNow();
    }

    private static void requireLive(Session session) {
        if (session.stopping.get()) {
            throw new WorkerUnavailableException();
        }
    }

    /**
     * Accepts an answer only if it succeeded and belongs to this lease and commit. A refusal
     * carries no detail to the caller, because a worker failure is not something the caller can
     * act on and its text could name host paths or command bytes.
     *
     * <p>The rejection code does reach the log, which is where an operator diagnoses this. It is
     * printed only when it matches the shape the protocol defines for a code, so a worker that
     * puts something else in that field cannot write arbitrary text into the log.
     */
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
                        && !response.path("commit").asString("").equals(session.commit))) {
            throw new WorkerUnavailableException();
        }
    }

    private static ExecutionResult result(JsonNode result, String copyId, String commit, CopyRetention retention) {
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
                artifacts.put(entry.getKey(), artifactMetadata(entry.getValue()));
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
                    commit,
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

    private record SessionKey(UUID account, WorkspaceId workspace, String scopeHash) {}

    private static void checkMaterialization(JsonNode response) {
        String code = response.path("code").asString("");
        if (code.equals("MATERIALIZE_CAPACITY")) {
            throw new MaterializationCapacity();
        }
    }

    private static final class MaterializationCapacity extends RuntimeException {}

    /**
     * An account copy's current execution lease. The busy claim serializes commands and principal
     * refreshes; changing grants fences the old lease before attaching the same files. Worker
     * requests remain bound to that lease's grant, while every host operation rechecks current
     * authorization. Closing a transport does not release this binding.
     *
     * <p>Command, renewal and revocation threads share the lifecycle latches. Capacity is released
     * only after confirmed containment, never merely because an RPC or a close request failed.
     */
    private static final class Session {
        private final UUID copyId;
        private final SessionKey key;
        private volatile AuthPrincipal principal;
        private final boolean fullRead;
        private volatile RepositorySnapshotExports.PublicExport publicExport;
        private final UUID leaseId;
        private final AtomicBoolean busy = new AtomicBoolean();
        private final AtomicBoolean stopping = new AtomicBoolean();
        private final AtomicBoolean renewing = new AtomicBoolean();
        private final CompletableFuture<Void> stopped = new CompletableFuture<>();
        private final CompletableFuture<Void> contained = new CompletableFuture<>();
        private volatile WorkerClient.Hello hello;
        private volatile String commit;
        private SelectedFileSaves.State saveState;
        private volatile AccountCopyRecord accountRecord;
        private volatile boolean openAttempted;
        private volatile boolean ready;
        private boolean attaching;
        private volatile boolean capacityReleased;
        private volatile boolean detached;
        private boolean auxiliary;
        private UUID retainedAppBoot;
        private UUID priorPrincipal;
        private String closeReason;
        private volatile long nextRenew;

        private Session(SessionKey key, AuthPrincipal principal, boolean fullRead, UUID copyId, UUID leaseId) {
            this.key = key;
            this.principal = principal;
            this.fullRead = fullRead;
            this.copyId = copyId;
            this.leaseId = leaseId;
        }

        private WorkerClient.Identity identity() {
            return new WorkerClient.Identity(
                    priorPrincipal == null ? principal.subjectId() : priorPrincipal,
                    principal.accountId(),
                    key.workspace().value(),
                    key.scopeHash(),
                    leaseId);
        }
    }
}
