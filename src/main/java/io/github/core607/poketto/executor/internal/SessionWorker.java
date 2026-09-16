package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/** Shared authorization and lease-bound worker requests for lifecycle and CLI operations. */
final class SessionWorker {
    private static final Logger log = LoggerFactory.getLogger(SessionWorker.class);
    private final AuthService auth;
    private final RepositorySnapshotExports exports;
    private final WorkerClient worker;

    SessionWorker(AuthService auth, RepositorySnapshotExports exports, WorkerClient worker) {
        this.auth = auth;
        this.exports = exports;
        this.worker = worker;
    }

    WorkspaceAccess authorize(AuthPrincipal principal, WorkspaceId workspace) {
        if (principal == null || principal.kind() != AuthPrincipal.Kind.API_KEY) {
            throw new SecurityException("Execution requires an API key");
        }
        return auth.authorize(principal, workspace, Capability.EXECUTE_REPOSITORY);
    }

    void authorize(ExecutionSession session) {
        if (session.fullRead) {
            auth.authorize(
                    session.principal, session.key.workspace(), Capability.READ_PRIVATE, Capability.EXECUTE_REPOSITORY);
        } else if (session.publicExport != null) {
            exports.requireCurrentPublic(session.principal, session.key.workspace(), session.publicExport);
        } else {
            authorize(session.principal, session.key.workspace());
        }
    }

    JsonNode request(ExecutionSession session, String operation, WorkerRequests.Data data, Duration timeout) {
        WorkerClient.PreparedRequest request;
        synchronized (session) {
            requireLive(session);
            request = worker.prepare(session.hello, session.identity(), operation, data);
        }
        return worker.send(request, timeout);
    }

    static void requireLive(ExecutionSession session) {
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
    static void requireOk(JsonNode response, ExecutionSession session) {
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

    static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for worker content verification", exception);
        }
    }
}
