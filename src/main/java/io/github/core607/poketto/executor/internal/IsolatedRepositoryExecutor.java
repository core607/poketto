package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.assets.MediaFileService;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthRevocation;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.McpSessionClosed;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final io.github.core607.poketto.content.PortableContentExports packages;
    private final WorkerClient worker;
    private final SelectedFileSaves saves;
    private final MediaFileService media;
    private final int maxSessions;
    private final Duration openTimeout;
    private final Duration closeTimeout;
    private final Map<SessionKey, Session> sessions = new LinkedHashMap<>();
    private final Semaphore executions = new Semaphore(4);
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
    private boolean closed;

    IsolatedRepositoryExecutor(
            io.github.core607.poketto.content.PortableContentExports packages,
            MediaFileService media,
            SelectedFileSaves saves,
            AuthService auth,
            RepositorySnapshotExports exports,
            WorkerClient worker,
            int maxSessions,
            Duration openTimeout,
            Duration closeTimeout) {
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
            Optional<String> requestedCommit,
            String command,
            Duration timeout,
            ExecutionCancellation cancellation) {
        var access = authorize(principal, workspace);
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
                    boolean fullRead = access.capabilities().contains(Capability.READ_PRIVATE);
                    if (!fullRead && requestedCommit.isPresent())
                        throw new IllegalArgumentException(
                                "Public execution starts from current publication; omit commit");
                    session = new Session(key, principal, fullRead);
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
                authorize(session);
                if (!session.ready) open(session, requestedCommit);
                requireLive(session);
                authorize(session);
                JsonNode response = executeWithBridge(session, command, timeout);
                requireOk(response, session);
                authorize(session);
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

    @Override
    public Optional<ArtifactChunk> readArtifact(
            AuthPrincipal principal,
            WorkspaceId workspace,
            String serverSessionId,
            String artifactId,
            long offset,
            int limit,
            ExecutionCancellation cancellation) {
        authorize(principal, workspace);
        if (serverSessionId == null
                || serverSessionId.isBlank()
                || serverSessionId.length() > 128
                || artifactId == null
                || !UUID.fromString(artifactId).toString().equals(artifactId)
                || offset < 0
                || offset > 128L * 1024 * 1024
                || limit < 1
                || limit > 65536) throw new IllegalArgumentException("Invalid artifact read");
        Session session;
        synchronized (this) {
            session = sessions.get(new SessionKey(principal.subjectId(), workspace, hash(serverSessionId)));
        }
        if (session == null) return Optional.empty();
        if (cancellation.isCancelled() || !executions.tryAcquire()) throw new WorkerUnavailableException();
        boolean ownsRead = false;
        try {
            if (!session.busy.compareAndSet(false, true)) throw new WorkerUnavailableException();
            ownsRead = true;
            try (var registration = cancellation.onCancel(() -> stopAndAwait(session, "cancelled"))) {
                requireLive(session);
                authorize(session);
                if (!session.ready) throw new WorkerUnavailableException();
                JsonNode response = requestLive(
                        session,
                        "ARTIFACT_READ",
                        Map.of("artifactId", artifactId, "offset", offset, "limit", limit),
                        Duration.ofSeconds(3));
                requireLive(session);
                authorize(session);
                String code = response.path("code").asString("");
                if (code.equals("ARTIFACT_UNAVAILABLE")) return Optional.empty();
                if (code.equals("INVALID_ARTIFACT_RANGE")) throw new IllegalArgumentException("Invalid artifact range");
                requireOk(response, session);
                Map<String, Object> metadata = artifactMetadata(response);
                long size = (long) metadata.get("bytes");
                if (!artifactId.equals(metadata.get("artifactId"))
                        || offset > size
                        || !response.path("offset").isIntegralNumber()
                        || response.path("offset").longValue() != offset
                        || !response.path("data").isString()
                        || response.path("data").stringValue().length() > 87384) throw new WorkerUnavailableException();
                byte[] bytes;
                try {
                    bytes = Base64.getDecoder().decode(response.path("data").stringValue());
                } catch (IllegalArgumentException invalid) {
                    throw new WorkerUnavailableException();
                }
                if (bytes.length != Math.min(limit, size - offset)) throw new WorkerUnavailableException();
                return Optional.of(new ArtifactChunk(
                        artifactId,
                        (String) metadata.get("name"),
                        (String) metadata.get("mediaType"),
                        size,
                        (String) metadata.get("sha256"),
                        (boolean) metadata.get("truncated"),
                        (int) metadata.get("expiresInSeconds"),
                        offset,
                        bytes));
            }
        } catch (RuntimeException failure) {
            if (ownsRead && !(failure instanceof IllegalArgumentException)) stop(session, "cancelled");
            throw failure;
        } finally {
            if (ownsRead) session.busy.set(false);
            executions.release();
        }
    }

    private static Map<String, Object> artifactMetadata(JsonNode value) {
        try {
            String id = value.path("artifactId").stringValue();
            String name = value.path("name").stringValue();
            String type = value.path("mediaType").stringValue();
            String digest = value.path("sha256").stringValue();
            long size = value.path("bytes").longValue();
            int expires = value.path("expiresInSeconds").intValue();
            if (!UUID.fromString(id).toString().equals(id)
                    || name.isEmpty()
                    || name.length() > 255
                    || name.contains("/")
                    || name.contains("\\")
                    || name.chars().anyMatch(c -> c < 32 || c == 127)
                    || type.length() > 128
                    || !type.matches("[a-z0-9.+-]+/[a-z0-9.+-]+")
                    || !digest.matches("[0-9a-f]{64}")
                    || !value.path("bytes").isIntegralNumber()
                    || size < 0
                    || size > 128L * 1024 * 1024
                    || !value.path("expiresInSeconds").isIntegralNumber()
                    || expires < 1
                    || expires > 300
                    || !value.path("truncated").isBoolean()) throw new WorkerUnavailableException();
            return Map.of(
                    "artifactId",
                    id,
                    "name",
                    name,
                    "mediaType",
                    type,
                    "bytes",
                    size,
                    "sha256",
                    digest,
                    "truncated",
                    value.path("truncated").booleanValue(),
                    "expiresInSeconds",
                    expires);
        } catch (RuntimeException invalid) {
            throw new WorkerUnavailableException();
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
                authorize(session);
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

    private io.github.core607.poketto.auth.WorkspaceAccess authorize(AuthPrincipal principal, WorkspaceId workspace) {
        if (principal == null || principal.kind() != AuthPrincipal.Kind.API_KEY)
            throw new SecurityException("Execution requires an API key");
        return auth.authorize(principal, workspace, Capability.EXECUTE_REPOSITORY);
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
        }
        for (Session session : current) {
            if (session.stopping.get()) {
                reconcileClose(session);
                continue;
            }
            if (!session.openAttempted
                    || session.stopping.get()
                    || System.nanoTime() < session.nextRenew
                    || !session.renewing.compareAndSet(false, true)) continue;
            try {
                controls.execute(() -> {
                    try {
                        if (session.stopping.get()) return;
                        authorize(session);
                        JsonNode response = requestLive(session, "RENEW", Map.of(), Duration.ofSeconds(3));
                        requireOk(response, session);
                        String state = response.path("state").asString("");
                        if (!List.of("INITIALIZING", "READY", "RUNNING").contains(state))
                            throw new WorkerUnavailableException();
                        session.nextRenew = System.nanoTime()
                                + Duration.ofSeconds(session.hello.renewAfterSeconds())
                                        .toNanos();
                    } catch (RuntimeException exception) {
                        log.warn(
                                "Worker lease renewal failed ({})",
                                exception.getClass().getSimpleName());
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
                || !session.renewing.compareAndSet(false, true)) return;
        Runnable deferred = () -> {
            session.nextRenew = System.nanoTime()
                    + Duration.ofSeconds(session.hello.renewAfterSeconds()).toNanos();
            session.renewing.set(false);
        };
        try {
            controls.execute(() -> {
                try {
                    if (session.capacityReleased || !session.stopping.get()) return;
                    // Closing remains allowed after revocation; no OPEN, EXEC or renewal is replayed.
                    closeWorker(session, session.closeReason);
                    releaseCapacity(session);
                } catch (RuntimeException exception) {
                    log.debug("Worker close remains unconfirmed; admission is retained");
                } finally {
                    deferred.run();
                }
            });
        } catch (RuntimeException exception) {
            deferred.run();
        }
    }

    private JsonNode executeWithBridge(Session session, String command, Duration timeout) {
        String executionId = UUID.randomUUID().toString();
        var running = commandIo.submit(() -> requestLive(
                session,
                "EXEC",
                Map.of(
                        "executionId",
                        executionId,
                        "commit",
                        session.commit,
                        "command",
                        command,
                        "timeoutMillis",
                        timeout.toMillis()),
                timeout.plusSeconds(5)));
        long deadline = System.nanoTime() + timeout.plusSeconds(5).toNanos();
        try {
            while (!running.isDone()) {
                if (session.stopping.get()) break;
                authorize(session);
                if (System.nanoTime() >= deadline) throw new WorkerUnavailableException();
                JsonNode polled;
                try {
                    polled = requestLive(session, "BRIDGE_POLL", Map.of(), Duration.ofSeconds(3));
                } catch (RuntimeException failed) {
                    if (session.stopping.get()) break;
                    throw failed;
                }
                if (running.isDone() || session.stopping.get()) break;
                if (!polled.path("ok").asBoolean(false)
                        && Set.of("LEASE_EXPIRED", "BRIDGE_UNAVAILABLE", "AUTH_REVOKED")
                                .contains(polled.path("code").asString(""))) break;
                requireOk(polled, session);
                JsonNode request = polled.path("bridgeRequest");
                if (request.isMissingNode() || request.isNull()) continue;
                if (!executionId.equals(polled.path("executionId").asString("")))
                    throw new WorkerUnavailableException();
                String requestId = request.path("requestId").stringValue();
                if (!UUID.fromString(requestId).toString().equals(requestId)) throw new WorkerUnavailableException();
                requireLive(session);
                authorize(session);
                Map<String, ?> reply = bridgeReply(session, executionId, request);
                requireLive(session);
                authorize(session);
                requireOk(
                        requestLive(
                                session,
                                "BRIDGE_COMPLETE",
                                Map.of("executionId", executionId, "bridgeRequestId", requestId, "response", reply),
                                Duration.ofSeconds(3)),
                        session);
            }
            // The EXEC reply owns its terminal result; closing the mailbox during cancellation
            // must not turn a confirmed cancelled command into an unknown transport outcome.
            return running.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WorkerUnavailableException();
        } catch (java.util.concurrent.ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException failure) throw failure;
            throw new WorkerUnavailableException();
        } catch (java.util.concurrent.TimeoutException expired) {
            throw new WorkerUnavailableException();
        } finally {
            running.cancel(true);
        }
    }

    private Map<String, ?> bridgeReply(Session session, String executionId, JsonNode request) {
        JsonNode arguments = request.path("arguments");
        if (!arguments.isObject()) throw new WorkerUnavailableException();
        if (request.path("operation").asString("").equals("status") && arguments.isEmpty())
            return Map.of(
                    "ok",
                    true,
                    "result",
                    Map.of(
                            "scope",
                            session.fullRead ? "full" : "public",
                            "baseCommit",
                            session.saveState.baseCommit,
                            "writeOutcomeUnknown",
                            session.saveState.uncertain
                                    || (session.saveState.move != null && session.saveState.move.result == null),
                            "movePending",
                            session.saveState.move != null,
                            "move",
                            session.saveState.move == null
                                    ? Map.of()
                                    : SessionMoves.pendingResult(session.saveState.move, "PENDING")
                                            .get("result"),
                            "lastSave",
                            session.saveState.lastSave,
                            "lastImport",
                            session.lastImport));
        String operation = request.path("operation").asString("");
        if (operation.equals("export")) {
            try {
                return exportPackage(session, executionId, arguments);
            } catch (IllegalArgumentException invalid) {
                return Map.of("ok", false, "code", "INVALID_EXPORT_SELECTION");
            } catch (io.github.core607.poketto.content.ContentExportException unavailable) {
                return Map.of(
                        "ok", false, "code", "EXPORT_" + unavailable.reason().name());
            } catch (io.github.core607.poketto.content.ContentRepositoryException
                    | io.github.core607.poketto.assets.AssetStorageException unavailable) {
                return Map.of("ok", false, "code", "EXPORT_UNAVAILABLE");
            }
        }
        if (operation.equals("artifact_create") || operation.equals("artifact_remove")) {
            try {
                Map<String, Object> data;
                if (operation.equals("artifact_create")) {
                    if (arguments.size() != 2
                            || !arguments.path("path").isString()
                            || !arguments.path("mediaType").isString()) throw new IllegalArgumentException();
                    data = Map.of(
                            "executionId",
                            executionId,
                            "path",
                            arguments.path("path").stringValue(),
                            "mediaType",
                            arguments.path("mediaType").stringValue());
                } else {
                    if (arguments.size() != 1 || !arguments.path("artifactId").isString())
                        throw new IllegalArgumentException();
                    String id = arguments.path("artifactId").stringValue();
                    if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
                    data = Map.of("artifactId", id);
                }
                authorize(session);
                JsonNode result = requestLive(
                        session,
                        operation.equals("artifact_create") ? "ARTIFACT_CREATE" : "ARTIFACT_REMOVE",
                        data,
                        Duration.ofSeconds(10));
                authorize(session);
                String code = result.path("code").asString("");
                if (Set.of("ARTIFACT_UNAVAILABLE", "ARTIFACT_CAPACITY", "INVALID_ARTIFACT")
                        .contains(code)) return Map.of("ok", false, "code", code);
                requireOk(result, session);
                return operation.equals("artifact_create")
                        ? Map.of("ok", true, "artifact", artifactMetadata(result.path("artifact")))
                        : Map.of("ok", true, "removed", true);
            } catch (IllegalArgumentException invalid) {
                return Map.of("ok", false, "code", "INVALID_ARTIFACT");
            }
        }
        if (operation.equals("media_fetch") || operation.equals("media_import") || operation.equals("media_list")) {
            try {
                if (operation.equals("media_list")) return listMedia(session, executionId, arguments);
                return operation.equals("media_fetch")
                        ? fetchMedia(session, executionId, arguments)
                        : importMedia(session, executionId, arguments);
            } catch (IllegalArgumentException invalid) {
                return Map.of("ok", false, "code", "INVALID_MEDIA_REQUEST");
            } catch (AuthException denied) {
                return Map.of("ok", false, "code", "ACCESS_DENIED");
            } catch (io.github.core607.poketto.assets.AssetStorageException unavailable) {
                return Map.of(
                        "ok",
                        false,
                        "code",
                        "MEDIA_UNAVAILABLE",
                        "reason",
                        unavailable.reason().name());
            } catch (io.github.core607.poketto.content.ContentRepositoryException unavailable) {
                return Map.of("ok", false, "code", "MEDIA_UNAVAILABLE");
            }
        }
        if (operation.equals("save")
                || operation.equals("recover")
                || operation.equals("sync")
                || operation.equals("move")) {
            if (!session.fullRead) return Map.of("ok", false, "code", "READ_ONLY_SCOPE");
            try {
                auth.authorize(
                        session.principal,
                        session.key.workspace(),
                        operation.equals("sync") ? Capability.READ_PRIVATE : Capability.WRITE_PRIVATE);
                if (operation.equals("recover")) {
                    boolean skipLocal =
                            arguments.size() == 1 && arguments.path("skipLocal").asBoolean(false);
                    if (!arguments.isEmpty() && !skipLocal) throw new IllegalArgumentException();
                    if (session.saveState.move != null) {
                        var recovered =
                                saves.moves().recover(session.principal, session.key.workspace(), session.saveState);
                        if (session.saveState.move == null || session.saveState.move.result == null) return recovered;
                        if (skipLocal) return saves.moves().skipLocal(session.saveState);
                        return moveFiles(session, executionId, session.saveState.move, true);
                    }
                    if (skipLocal) throw new IllegalArgumentException("no confirmed move to skip");
                    return saves.recover(session.principal, session.key.workspace(), session.saveState);
                }
                if (session.saveState.move != null)
                    return SessionMoves.pendingResult(session.saveState.move, "RECOVER_MOVE_FIRST");
                if (session.saveState.uncertain) return Map.of("ok", false, "code", "WRITE_OUTCOME_UNKNOWN");
                if (operation.equals("move")) {
                    if (arguments.size() != 2
                            || !arguments.path("source").isString()
                            || !arguments.path("destination").isString()) throw new IllegalArgumentException();
                    var pending = saves.moves()
                            .prepare(
                                    session.principal,
                                    session.key.workspace(),
                                    session.saveState,
                                    arguments.path("source").stringValue(),
                                    arguments.path("destination").stringValue(),
                                    captureOptional(session, executionId, RepositoryMediaIndex.PATH));
                    return moveFiles(session, executionId, pending, false);
                }
                if (operation.equals("sync")) {
                    if (arguments.size() != 1 || !arguments.path("path").isString())
                        throw new IllegalArgumentException();
                    String path = arguments.path("path").stringValue();
                    JsonNode manifest = requestLive(
                            session,
                            "CAPTURE_OPTIONAL",
                            Map.of("executionId", executionId, "path", path),
                            Duration.ofSeconds(5));
                    if (manifest.path("code").asString("").equals("CAPTURE_REJECTED"))
                        throw new IllegalArgumentException();
                    requireOk(manifest, session);
                    List<String> absent = selectedPaths(manifest.path("absent"));
                    if (!absent.isEmpty() && !absent.equals(List.of(path))) throw new WorkerUnavailableException();
                    var captured = readCapture(
                            session, executionId, manifest, absent.isEmpty() ? List.of(path) : List.of(), List.of());
                    var plan = saves.prepareSync(
                            session.principal,
                            session.key.workspace(),
                            session.saveState,
                            path,
                            Optional.ofNullable(captured.get(path)));
                    return synchronizeFile(session, executionId, plan);
                }
                if (arguments.size() != 2 || !arguments.has("writes") || !arguments.has("deletes"))
                    throw new IllegalArgumentException();
                List<String> writes = selectedPaths(arguments.path("writes"));
                List<String> deletes = selectedPaths(arguments.path("deletes"));
                if (writes.size() + deletes.size() < 1 || writes.size() + deletes.size() > 64)
                    throw new IllegalArgumentException();
                var captured = capture(session, executionId, writes, deletes);
                requireLive(session);
                authorize(session);
                return saves.save(session.principal, session.key.workspace(), session.saveState, captured, deletes);
            } catch (IllegalArgumentException invalid) {
                return Map.of("ok", false, "code", "INVALID_SELECTION");
            } catch (AuthException denied) {
                return Map.of("ok", false, "code", "ACCESS_DENIED");
            } catch (io.github.core607.poketto.content.ContentRepositoryException unavailable) {
                return Map.of("ok", false, "code", "REPOSITORY_UNAVAILABLE");
            }
        }
        return Map.of("ok", false, "code", "OPERATION_UNAVAILABLE");
    }

    private static List<String> selectedPaths(JsonNode values) {
        if (!values.isArray() || values.size() > 64) throw new IllegalArgumentException();
        var paths = new ArrayList<String>();
        for (JsonNode value : values) {
            if (!value.isString()
                    || value.stringValue().isEmpty()
                    || value.stringValue().getBytes(StandardCharsets.UTF_8).length > 4096)
                throw new IllegalArgumentException();
            paths.add(value.stringValue());
        }
        return paths;
    }

    private Map<String, ?> moveFiles(
            Session session, String executionId, SessionMoves.Pending pending, boolean recovery) {
        byte[] payload = pending.payload;
        JsonNode begun = requestLive(
                session,
                "MOVE_BEGIN",
                Map.of(
                        "executionId",
                        executionId,
                        "bytes",
                        payload.length,
                        "sha256",
                        io.github.core607.poketto.content.DocumentRevision.sha256(payload)
                                .value()
                                .substring(7)),
                Duration.ofSeconds(3));
        if (begun.path("code").asString("").equals("MOVE_REJECTED")) {
            if (recovery) return SessionMoves.pendingResult(pending, "LOCAL_MOVE_PENDING");
            return Map.of("ok", false, "code", "LOCAL_MOVE_REJECTED");
        }
        requireOk(begun, session);
        String transfer = begun.path("transferId").asString("");
        if (!UUID.fromString(transfer).toString().equals(transfer)) throw new WorkerUnavailableException();
        var reference = Map.of("executionId", executionId, "transferId", transfer);
        try {
            for (int offset = 0; offset < payload.length; offset += 65536) {
                authorize(session);
                int end = Math.min(offset + 65536, payload.length);
                JsonNode chunk = requestLive(
                        session,
                        "MOVE_CHUNK",
                        Map.of(
                                "executionId",
                                executionId,
                                "transferId",
                                transfer,
                                "offset",
                                offset,
                                "data",
                                Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(payload, offset, end))),
                        Duration.ofSeconds(3));
                if (chunk.path("code").asString("").equals("MOVE_REJECTED")) {
                    if (recovery) return SessionMoves.pendingResult(pending, "LOCAL_MOVE_PENDING");
                    return Map.of("ok", false, "code", "LOCAL_MOVE_REJECTED");
                }
                requireOk(chunk, session);
                if (chunk.path("receivedBytes").asInt(-1) != end) throw new WorkerUnavailableException();
            }
            if (!recovery) {
                JsonNode checked = requestLive(session, "MOVE_CHECK", reference, Duration.ofSeconds(15));
                if (checked.path("code").asString("").equals("MOVE_REJECTED"))
                    return Map.of("ok", false, "code", "LOCAL_MOVE_REJECTED");
                requireOk(checked, session);
                if (!checked.path("checked").path("ready").asBoolean(false)) throw new WorkerUnavailableException();
                authorize(session);
                var committed =
                        saves.moves().commit(session.principal, session.key.workspace(), session.saveState, pending);
                if (session.saveState.move == null || pending.result == null) return committed;
            }
            authorize(session);
            JsonNode installed;
            try {
                installed = requestLive(session, "MOVE_COMMIT", reference, Duration.ofSeconds(15));
            } catch (WorkerUnavailableException uncertainInstallation) {
                // Git is acknowledged. Keep the plan so a live worker can reconcile its receipt.
                return SessionMoves.pendingResult(pending, "LOCAL_MOVE_PENDING");
            }
            if (installed.path("code").asString("").equals("MOVE_REJECTED"))
                return SessionMoves.pendingResult(pending, "LOCAL_MOVE_CONFLICT");
            requireOk(installed, session);
            if (installed.path("installed").path("changedPaths").asInt(-1) != pending.paths.size()
                    || !installed.path("installed").path("alreadyApplied").isBoolean())
                throw new WorkerUnavailableException();
            return saves.moves().installed(session.saveState);
        } finally {
            try {
                requestLive(session, "MOVE_ABORT", reference, Duration.ofSeconds(3));
            } catch (RuntimeException cleanupFailure) {
                log.warn("Worker move staging cleanup was not acknowledged; command cleanup will release its slot");
            }
        }
    }

    private Map<String, String> capture(
            Session session, String executionId, List<String> writes, List<String> deletes) {
        JsonNode manifest = requestLive(
                session,
                "CAPTURE_BEGIN",
                Map.of("executionId", executionId, "writes", writes, "deletes", deletes),
                Duration.ofSeconds(5));
        return readCapture(session, executionId, manifest, writes, deletes);
    }

    private Map<String, String> readCapture(
            Session session, String executionId, JsonNode manifest, List<String> writes, List<String> deletes) {
        if (manifest.path("code").asString("").equals("CAPTURE_REJECTED")) throw new IllegalArgumentException();
        requireOk(manifest, session);
        String captureId = manifest.path("captureId").asString("");
        if (!UUID.fromString(captureId).toString().equals(captureId)) throw new WorkerUnavailableException();
        Map<String, ?> reference = Map.of("executionId", executionId, "captureId", captureId);
        try {
            JsonNode files = manifest.path("writes");
            if (!files.isArray()
                    || files.size() != writes.size()
                    || !selectedPaths(manifest.path("deletes")).equals(deletes)) throw new WorkerUnavailableException();
            var result = new LinkedHashMap<String, String>();
            long total = 0;
            for (int index = 0; index < files.size(); index++) {
                JsonNode file = files.get(index);
                String path = file.path("path").asString("");
                long size = file.path("bytes").asLong(-1);
                total += size;
                if (!path.equals(writes.get(index)) || result.containsKey(path) || size < 0 || total > 4 * 1024 * 1024)
                    throw new WorkerUnavailableException();
                var bytes = new ByteArrayOutputStream((int) size);
                while (bytes.size() < size) {
                    authorize(session);
                    int limit = (int) Math.min(65536, size - bytes.size());
                    JsonNode chunk = requestLive(
                            session,
                            "CAPTURE_READ",
                            Map.of(
                                    "executionId",
                                    executionId,
                                    "captureId",
                                    captureId,
                                    "index",
                                    index,
                                    "offset",
                                    bytes.size(),
                                    "limit",
                                    limit),
                            Duration.ofSeconds(3));
                    requireOk(chunk, session);
                    if (!captureId.equals(chunk.path("captureId").asString(""))
                            || chunk.path("index").asInt(-1) != index
                            || chunk.path("offset").asInt(-1) != bytes.size()) throw new WorkerUnavailableException();
                    byte[] block = Base64.getDecoder().decode(chunk.path("data").asString(""));
                    if (block.length != limit) throw new WorkerUnavailableException();
                    bytes.writeBytes(block);
                }
                byte[] content = bytes.toByteArray();
                try {
                    if (!HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(content))
                            .equals(file.path("sha256").asString(""))) throw new WorkerUnavailableException();
                    result.put(
                            path,
                            StandardCharsets.UTF_8
                                    .newDecoder()
                                    .onMalformedInput(CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                                    .decode(ByteBuffer.wrap(content))
                                    .toString());
                } catch (java.security.NoSuchAlgorithmException | java.nio.charset.CharacterCodingException invalid) {
                    throw new WorkerUnavailableException();
                }
            }
            return result;
        } finally {
            requireOk(requestLive(session, "CAPTURE_RELEASE", reference, Duration.ofSeconds(3)), session);
        }
    }

    private Map<String, ?> synchronizeFile(Session session, String executionId, SelectedFileSaves.SyncPlan plan) {
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
        if (!installed) return Map.of("ok", false, "code", "LOCAL_UPDATE_REJECTED");
        saves.acknowledgeSync(session.saveState, plan);
        return Map.of(
                "ok",
                !plan.conflicted(),
                "code",
                plan.conflicted() ? "MERGE_CONFLICT" : "SYNCHRONIZED",
                "result",
                Map.of(
                        "path",
                        plan.path(),
                        "baseCommit",
                        plan.remoteCommit(),
                        "saved",
                        false,
                        "conflicted",
                        plan.conflicted()));
    }

    private Map<String, ?> listMedia(Session session, String executionId, JsonNode arguments) {
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
            if (query.commit() != null)
                throw new IllegalArgumentException("public media uses only its current projection");
            files = new LinkedHashMap<>();
            session.publicExport.media().forEach((path, media) -> files.put(path, media.original()));
            source = "public-projection";
            version = session.publicExport.projectionSha256();
        }
        authorize(session);
        return MediaListing.page(files, query, version, source);
    }

    private Map<String, ?> fetchMedia(Session session, String executionId, JsonNode arguments) {
        if (arguments.size() != 3
                || !arguments.path("path").isString()
                || !(arguments.path("commit").isNull()
                        || arguments.path("commit").isString())
                || !(arguments.path("output").isNull()
                        || arguments.path("output").isString())) throw new IllegalArgumentException();
        String path = arguments.path("path").stringValue();
        String destination = arguments.path("output").isNull()
                ? path
                : arguments.path("output").stringValue();
        Optional<String> requested = arguments.path("commit").isNull()
                ? Optional.empty()
                : Optional.of(arguments.path("commit").stringValue());
        MediaFileService.Download download;
        String commit = null;
        String indexSource;
        if (session.fullRead) {
            if (requested.isPresent()) {
                commit = requested.orElseThrow();
                if (!commit.matches("[0-9a-f]{40}")) throw new IllegalArgumentException();
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
            if (requested.isPresent())
                throw new IllegalArgumentException("public media uses only its current projection");
            var approved = session.publicExport.media().get(path);
            if (approved == null) return Map.of("ok", false, "code", "MEDIA_UNAVAILABLE");
            download = media.publicDownload(
                    session.key.workspace(),
                    approved.route(),
                    session.publicExport.sourcePaths().get(path));
            var expected = approved.original();
            var asset = download.asset();
            if (!asset.reference().assetId().equals(expected.assetId())
                    || !asset.reference().revision().equals(expected.revision())
                    || asset.size() != expected.size()
                    || !asset.mediaType().equals(expected.mediaType()))
                return Map.of("ok", false, "code", "MEDIA_UNAVAILABLE");
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
        if (!installed)
            return Map.of(
                    "ok",
                    false,
                    "code",
                    "LOCAL_FILE_EXISTS",
                    "message",
                    "A different local file exists; keep it or choose another --output path.");
        var result = new LinkedHashMap<String, Object>();
        result.put("path", destination);
        result.put("sourcePath", path);
        result.put("indexSource", indexSource);
        if (commit != null) result.put("commit", commit);
        result.put("sha256", asset.reference().revision());
        result.put("mediaType", asset.mediaType());
        result.put("bytes", asset.size());
        return Map.of("ok", true, "result", result);
    }

    private Optional<String> captureOptional(Session session, String executionId, String path) {
        JsonNode manifest = requestLive(
                session, "CAPTURE_OPTIONAL", Map.of("executionId", executionId, "path", path), Duration.ofSeconds(5));
        if (manifest.path("code").asString("").equals("CAPTURE_REJECTED")) throw new IllegalArgumentException();
        requireOk(manifest, session);
        List<String> absent = selectedPaths(manifest.path("absent"));
        if (!absent.isEmpty() && !absent.equals(List.of(path))) throw new WorkerUnavailableException();
        return Optional.ofNullable(
                readCapture(session, executionId, manifest, absent.isEmpty() ? List.of(path) : List.of(), List.of())
                        .get(path));
    }

    private Map<String, ?> importMedia(Session session, String executionId, JsonNode arguments) {
        if (!session.fullRead) return Map.of("ok", false, "code", "READ_ONLY_SCOPE");
        auth.authorize(session.principal, session.key.workspace(), Capability.WRITE_PRIVATE);
        if (arguments.size() != 5
                || !arguments.path("file").isString()
                || !arguments.path("path").isString()
                || !arguments.path("mediaType").isString()
                || !arguments.path("key").isString()
                || !arguments.path("replace").isBoolean()) throw new IllegalArgumentException();
        String file = arguments.path("file").stringValue(),
                path = arguments.path("path").stringValue();
        String mediaType = arguments.path("mediaType").stringValue(),
                key = arguments.path("key").stringValue();
        boolean replace = arguments.path("replace").booleanValue();
        if (!key.matches("[A-Za-z0-9_-]{16,128}")) throw new IllegalArgumentException();
        io.github.core607.poketto.assets.ManagedAsset.validateMediaType(mediaType);
        Optional<String> source = captureOptional(session, executionId, RepositoryMediaIndex.PATH);
        if (source.isEmpty()
                && !saves.baselineFile(
                                session.principal,
                                session.key.workspace(),
                                session.saveState,
                                RepositoryMediaIndex.PATH)
                        .expectedAbsence())
            return Map.of(
                    "ok",
                    false,
                    "code",
                    "INDEX_MISSING",
                    "message",
                    "Restore or intentionally recreate the local media index before importing.");
        var index = source.map(value -> RepositoryMediaIndex.parse(value.getBytes(StandardCharsets.UTF_8)))
                .orElseGet(RepositoryMediaIndex::empty);
        var entries = new LinkedHashMap<>(index.files());
        entries.put(path, new RepositoryMediaIndex.Media(new UUID(0, 0), "0".repeat(64), mediaType, 1));
        new RepositoryMediaIndex(entries); // Validate the complete logical namespace before uploading.
        var existingGit = saves.baselineFile(session.principal, session.key.workspace(), session.saveState, path);
        if (!existingGit.expectedAbsence()
                && existingGit.diagnostics().stream()
                        .noneMatch(value -> value.code().equals("MANAGED_MEDIA")))
            return Map.of("ok", false, "code", "MEDIA_PATH_COLLIDES_WITH_GIT");
        JsonNode manifest = requestLive(
                session, "CAPTURE_BINARY", Map.of("executionId", executionId, "path", file), Duration.ofSeconds(8));
        if (manifest.path("code").asString("").equals("CAPTURE_REJECTED")) throw new IllegalArgumentException();
        requireOk(manifest, session);
        String captureId = manifest.path("captureId").asString("");
        if (!UUID.fromString(captureId).toString().equals(captureId)) throw new WorkerUnavailableException();
        Map<String, ?> reference = Map.of("executionId", executionId, "captureId", captureId);
        io.github.core607.poketto.assets.ManagedAsset asset;
        try {
            JsonNode files = manifest.path("writes");
            if (!files.isArray()
                    || files.size() != 1
                    || !files.get(0).path("path").asString("").equals(file)) throw new WorkerUnavailableException();
            long size = files.get(0).path("bytes").asLong(-1);
            String digest = files.get(0).path("sha256").asString("");
            var previous = index.files().get(path);
            if (!replace
                    && previous != null
                    && (previous.size() != size
                            || !previous.revision().equals(digest)
                            || !previous.mediaType().equals(mediaType)))
                return Map.of("ok", false, "code", "MEDIA_PATH_EXISTS");
            try (var input = new CapturedBinaryInput(size, digest, (offset, limit) -> {
                authorize(session);
                JsonNode chunk = requestLive(
                        session,
                        "CAPTURE_READ",
                        Map.of(
                                "executionId",
                                executionId,
                                "captureId",
                                captureId,
                                "index",
                                0,
                                "offset",
                                offset,
                                "limit",
                                limit),
                        Duration.ofSeconds(3));
                requireOk(chunk, session);
                if (!captureId.equals(chunk.path("captureId").asString(""))
                        || chunk.path("index").asInt(-1) != 0
                        || chunk.path("offset").asLong(-1) != offset) throw new WorkerUnavailableException();
                return Base64.getDecoder().decode(chunk.path("data").asString(""));
            })) {
                asset = media.upload(session.principal, session.key.workspace(), key, mediaType, input);
                if (!input.verified()
                        || asset.size() != size
                        || !asset.reference().revision().equals(digest)
                        || !asset.mediaType().equals(mediaType)) throw new WorkerUnavailableException();
            }
        } finally {
            try {
                requestLive(session, "CAPTURE_RELEASE", reference, Duration.ofSeconds(3));
            } catch (RuntimeException cleanupFailure) {
                log.warn("Binary capture release was not acknowledged; command cleanup will release its staging file");
            }
        }
        session.lastImport = importReceipt(path, asset, false);
        var entry = new RepositoryMediaIndex.Media(
                asset.reference().assetId(), asset.reference().revision(), asset.mediaType(), asset.size());
        var previous = index.files().get(path);
        if (previous != null && !previous.equals(entry) && !replace)
            return Map.of("ok", false, "code", "MEDIA_PATH_EXISTS", "result", session.lastImport);
        entries.put(path, entry);
        byte[] changed = entry.equals(previous)
                ? source.orElseThrow().getBytes(StandardCharsets.UTF_8)
                : new RepositoryMediaIndex(entries).encode();
        String text = new String(changed, StandardCharsets.UTF_8);
        if (!materialize(
                session,
                executionId,
                RepositoryMediaIndex.PATH,
                changed.length,
                hash(text),
                source.map(IsolatedRepositoryExecutor::hash).orElse(null),
                false,
                false,
                output -> output.write(changed)))
            return Map.of("ok", false, "code", "INDEX_CHANGED", "result", session.lastImport);
        session.lastImport = importReceipt(path, asset, true);
        return Map.of("ok", true, "result", session.lastImport);
    }

    private static Map<String, ?> importReceipt(
            String path, io.github.core607.poketto.assets.ManagedAsset asset, boolean indexed) {
        return Map.of(
                "path",
                path,
                "assetId",
                asset.reference().assetId().toString(),
                "sha256",
                asset.reference().revision(),
                "mediaType",
                asset.mediaType(),
                "bytes",
                asset.size(),
                "originalStored",
                true,
                "indexUpdated",
                indexed,
                "saved",
                false);
    }

    @FunctionalInterface
    private interface FileSource {
        void writeTo(java.io.OutputStream output) throws java.io.IOException;
    }

    private Map<String, ?> exportPackage(Session session, String executionId, JsonNode arguments) {
        if (arguments.size() != 3
                || !arguments.path("paths").isArray()
                || !arguments.path("output").isString()
                || !arguments.path("publicOnly").isBoolean()) throw new IllegalArgumentException();
        var selections = new ArrayList<String>();
        for (JsonNode path : arguments.path("paths")) {
            if (!path.isString()) throw new IllegalArgumentException();
            selections.add(path.stringValue());
        }
        String output = arguments.path("output").stringValue();
        SessionExportSelection.validate(output);
        synchronized (session) {
            requireLive(session);
        }
        authorize(session);
        var selected = SessionExportSelection.resolve(selections, session.fullRead ? null : session.publicExport);
        boolean publicOnly = !session.fullRead || arguments.path("publicOnly").booleanValue();
        var client = Optional.of(session.key.sessionHash());
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
                            session.principal, session.key.workspace(), receipt.handle(), client, sink)))
                return Map.of("ok", false, "code", "LOCAL_FILE_CHANGED");
            return Map.of(
                    "ok",
                    true,
                    "result",
                    Map.of(
                            "path",
                            output,
                            "bytes",
                            receipt.bytes(),
                            "sha256",
                            receipt.sha256(),
                            "scope",
                            publicOnly ? "public" : "private",
                            "saved",
                            false));
        } finally {
            // Covers a close event that raced before create registered its build. No package handle
            // is exposed to the sandbox; every command owns only its materialized local result.
            packages.closeClient(session.principal, session.key.workspace(), session.key.sessionHash());
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
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("executionId", executionId);
        metadata.put("path", path);
        metadata.put("bytes", size);
        metadata.put("sha256", digest);
        metadata.put("expectedSha256", expected);
        metadata.put("delete", delete);
        metadata.put("allowIdentical", allowIdentical);
        JsonNode begun = requestLive(session, "MATERIALIZE_BEGIN", metadata, Duration.ofSeconds(3));
        if (begun.path("code").asString("").equals("MATERIALIZE_REJECTED")) throw new IllegalArgumentException();
        requireOk(begun, session);
        String transferId = begun.path("transferId").asString("");
        if (!UUID.fromString(transferId).toString().equals(transferId)) throw new WorkerUnavailableException();
        Map<String, ?> reference = Map.of("executionId", executionId, "transferId", transferId);
        try {
            var sink = new java.io.OutputStream() {
                long sent;

                @Override
                public void write(int value) {
                    write(new byte[] {(byte) value}, 0, 1);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) {
                    java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
                    if (length > size - sent)
                        throw new IllegalArgumentException("media source exceeds its declared size");
                    while (length > 0) {
                        authorize(session);
                        int count = Math.min(65536, length);
                        JsonNode chunk = requestLive(
                                session,
                                "MATERIALIZE_CHUNK",
                                Map.of(
                                        "executionId",
                                        executionId,
                                        "transferId",
                                        transferId,
                                        "offset",
                                        sent,
                                        "data",
                                        Base64.getEncoder()
                                                .encodeToString(
                                                        java.util.Arrays.copyOfRange(bytes, offset, offset + count))),
                                Duration.ofSeconds(3));
                        requireOk(chunk, session);
                        sent += count;
                        if (chunk.path("receivedBytes").asLong(-1) != sent) throw new WorkerUnavailableException();
                        offset += count;
                        length -= count;
                    }
                }
            };
            var output = new java.io.BufferedOutputStream(sink, 65536);
            // Flush only after the source succeeds. Closing on failure could replay a buffered chunk.
            source.writeTo(output);
            output.flush();
            if (sink.sent != size) throw new IllegalArgumentException("media source is incomplete");
            authorize(session);
            JsonNode committed = requestLive(session, "MATERIALIZE_COMMIT", reference, Duration.ofSeconds(5));
            if (committed.path("code").asString("").equals("MATERIALIZE_REJECTED")) return false;
            requireOk(committed, session);
            JsonNode installed = committed.path("installed");
            if (!installed.path("path").asString("").equals(path)
                    || (delete
                            ? !installed.path("sha256").isNull()
                            : !installed.path("sha256").asString("").equals(digest)))
                throw new WorkerUnavailableException();
            return true;
        } catch (java.io.IOException failure) {
            throw new WorkerUnavailableException();
        } finally {
            try {
                requestLive(session, "MATERIALIZE_ABORT", reference, Duration.ofSeconds(3));
            } catch (RuntimeException cleanupFailure) {
                log.warn("Worker transfer cleanup was not acknowledged; command cleanup will release its slot");
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
            session.closeReason = reason;
            packages.closeClient(session.principal, session.key.workspace(), session.key.sessionHash());
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
        commandIo.shutdownNow();
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
                        case "lease_expired", "sandbox_failed" -> TerminationReason.SANDBOX_FAILURE;
                        default ->
                            TerminationReason.valueOf(result.path("terminationReason")
                                    .stringValue()
                                    .toUpperCase(Locale.ROOT));
                    };
            boolean timedOut = result.path("timedOut").booleanValue();
            if (timedOut != (reason == TerminationReason.TIMEOUT)) throw new WorkerUnavailableException();
            if (!result.path("artifacts").isObject()
                    || !result.path("artifactErrors").isObject()
                    || result.path("artifacts").size() > 2
                    || result.path("artifactErrors").size() > 2) throw new WorkerUnavailableException();
            Map<String, Map<String, Object>> artifacts = new LinkedHashMap<>();
            Map<String, String> artifactErrors = new LinkedHashMap<>();
            for (var entry : result.path("artifacts").properties()) {
                if (!Set.of("stdout", "stderr").contains(entry.getKey())) throw new WorkerUnavailableException();
                artifacts.put(entry.getKey(), artifactMetadata(entry.getValue()));
            }
            for (var entry : result.path("artifactErrors").properties()) {
                if (!Set.of("stdout", "stderr").contains(entry.getKey())
                        || artifacts.containsKey(entry.getKey())
                        || !Set.of("ARTIFACT_UNAVAILABLE", "ARTIFACT_CAPACITY")
                                .contains(entry.getValue().asString(""))) throw new WorkerUnavailableException();
                artifactErrors.put(entry.getKey(), entry.getValue().stringValue());
            }
            return new ExecutionResult(
                    commit,
                    result.path("exitCode").intValue(),
                    stdout,
                    stderr,
                    result.path("stdoutTruncated").booleanValue(),
                    result.path("stderrTruncated").booleanValue(),
                    timedOut,
                    reason,
                    artifacts,
                    artifactErrors);
        } catch (RuntimeException exception) {
            String reason = result.path("terminationReason").asString("");
            log.warn(
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

    private record SessionKey(UUID principal, WorkspaceId workspace, String sessionHash) {}

    private static final class Session {
        private final SessionKey key;
        private final AuthPrincipal principal;
        private final boolean fullRead;
        private volatile RepositorySnapshotExports.PublicExport publicExport;
        private final UUID leaseId = UUID.randomUUID();
        private final AtomicBoolean busy = new AtomicBoolean();
        private final AtomicBoolean stopping = new AtomicBoolean();
        private final AtomicBoolean renewing = new AtomicBoolean();
        private final CompletableFuture<Void> stopped = new CompletableFuture<>();
        private volatile WorkerClient.Hello hello;
        private volatile String commit;
        private SelectedFileSaves.State saveState;
        private Map<String, ?> lastImport = Map.of();
        private volatile boolean openAttempted;
        private volatile boolean ready;
        private volatile boolean capacityReleased;
        private volatile boolean detached;
        private String closeReason;
        private volatile long nextRenew;

        private Session(SessionKey key, AuthPrincipal principal, boolean fullRead) {
            this.key = key;
            this.principal = principal;
            this.fullRead = fullRead;
        }

        private WorkerClient.Identity identity() {
            return new WorkerClient.Identity(
                    key.principal(), principal.accountId(), key.workspace().value(), key.sessionHash(), leaseId);
        }
    }
}
