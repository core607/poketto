package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.SessionWorker.requireLive;
import static io.github.core607.poketto.executor.internal.SessionWorker.requireOk;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.RepositoryExecutor.ArtifactChunk;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Reading a command's retained output: the account's copies are searched in reading-scope order,
 * and a chunk is served under the same operation admission, lease refresh and liveness checks a
 * command takes, so a revoked or replaced lease cannot keep streaming what it produced.
 */
final class SessionArtifacts {
    private final SessionRegistry registry;
    private final SessionWorker io;
    private final SessionLifecycle lifecycle;
    private final AccountCopyStore accounts;

    SessionArtifacts(
            SessionRegistry registry, SessionWorker io, SessionLifecycle lifecycle, AccountCopyStore accounts) {
        this.registry = registry;
        this.io = io;
        this.lifecycle = lifecycle;
        this.accounts = accounts;
    }

    Optional<ArtifactChunk> read(
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
                var page = readAccount(
                        principal, workspace, serverSessionId, artifactId, offset, limit, cancellation, held);
                if (page.isPresent()) {
                    return page;
                }
            } catch (RuntimeException failure) {
                throw IsolatedRepositoryExecutor.admissionFailure(failure, null);
            }
        }
        return Optional.empty();
    }

    private Optional<ArtifactChunk> readAccount(
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
        ExecutionSession session = registry.session(SessionLifecycle.keyFor(held.owner()));
        if (session == null) {
            return Optional.empty();
        }
        if (cancellation.isCancelled()) {
            throw new WorkerUnavailableException();
        }
        if (!registry.tryAcquireOperation()) {
            throw registry.rejected("operation_limit");
        }
        boolean ownsRead = false;
        try {
            if (!session.busy.compareAndSet(false, true)) {
                throw registry.rejected("session_busy");
            }
            ownsRead = true;
            session = lifecycle.refreshLease(session, principal);
            held.bind(lifecycle.writer(session));
            session.accountRecord = held.record();
            ExecutionSession active = session;
            try (var registration = cancellation.onCancel(() -> lifecycle.stopAndAwait(active, "cancelled"))) {
                requireLive(session);
                io.authorize(session);
                if (!session.ready && session.attaching) {
                    lifecycle.attach(session);
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
                lifecycle.stop(session, "cancelled");
            }
            throw failure;
        } finally {
            if (ownsRead) {
                session.busy.set(false);
            }
            registry.releaseOperation();
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
}
