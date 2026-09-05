package io.github.core607.poketto.mcp;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Duration;
import java.util.Optional;

/** Execution boundary supplied only by a verified isolated worker; no ordinary subprocess fallback. */
public interface RepositoryExecutor {
    /**
     * The session id comes from the server SDK, after principal/workspace binding validation.
     * Omitted commits retain this execution session's pinned commit. Implementations own command,
     * output, process-tree, filesystem, network, cancellation, and lease limits.
     */
    ExecutionResult execute(
            AuthPrincipal principal,
            WorkspaceId workspace,
            String serverSessionId,
            Optional<String> commit,
            String command,
            Duration timeout);

    record ExecutionResult(
            String commit,
            int exitCode,
            String stdout,
            String stderr,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            boolean timedOut) {}
}
