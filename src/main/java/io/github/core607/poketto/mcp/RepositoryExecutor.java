package io.github.core607.poketto.mcp;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** Execution boundary supplied only by a verified isolated worker; no ordinary subprocess fallback. */
public interface RepositoryExecutor {
    /**
     * The session id comes from the server SDK, after principal/workspace binding validation.
     * Omitted commits retain this execution session's pinned commit. Implementations own command,
     * output, process-tree, filesystem, network, cancellation, and lease limits.
     * Worker lifecycle synchronization must prevent process creation after cancellation and make
     * every registered termination callback stop the complete command process tree.
     */
    ExecutionResult execute(
            AuthPrincipal principal,
            WorkspaceId workspace,
            String serverSessionId,
            Optional<String> commit,
            String command,
            Duration timeout,
            ExecutionCancellation cancellation);

    /** Reads one bounded chunk from this live MCP session; unknown or expired artifacts return empty. */
    Optional<ArtifactChunk> readArtifact(
            AuthPrincipal principal,
            WorkspaceId workspace,
            String serverSessionId,
            String artifactId,
            long offset,
            int limit,
            ExecutionCancellation cancellation);

    record ArtifactChunk(
            String artifactId,
            String name,
            String mediaType,
            long size,
            String sha256,
            boolean truncated,
            int expiresInSeconds,
            long offset,
            byte[] bytes) {
        public ArtifactChunk {
            if (bytes.length > 65536) {
                throw new IllegalArgumentException("Artifact chunk exceeds its bound");
            }
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /** Immutable metadata for one retained artifact, as the worker reported it. */
    record ArtifactMetadata(
            String artifactId,
            String name,
            String mediaType,
            long bytes,
            String sha256,
            boolean truncated,
            int expiresInSeconds) {}

    record ExecutionResult(
            String commit,
            int exitCode,
            String stdout,
            String stderr,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            boolean timedOut,
            TerminationReason terminationReason,
            Map<String, ArtifactMetadata> artifacts,
            Map<String, String> artifactErrors) {
        public ExecutionResult {
            artifacts = Map.copyOf(artifacts);
            artifactErrors = Map.copyOf(artifactErrors);
        }
    }

    enum TerminationReason {
        NORMAL,
        TIMEOUT,
        OUTPUT_LIMIT,
        RESOURCE_LIMIT,
        CANCELLED,
        REVOKED,
        SANDBOX_FAILURE
    }
}
