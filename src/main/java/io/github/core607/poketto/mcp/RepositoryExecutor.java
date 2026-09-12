package io.github.core607.poketto.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

    /**
     * Immutable metadata for one retained artifact, as the worker reported it. The rules below are
     * what makes such a report usable: the name reaches a client as a file name, so it carries no
     * separator and no control character, and the lifetime and size are the worker's own bounds.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ArtifactMetadata(
            String artifactId,
            String name,
            String mediaType,
            long bytes,
            String sha256,
            boolean truncated,
            int expiresInSeconds) {
        public ArtifactMetadata {
            if (artifactId == null || !UUID.fromString(artifactId).toString().equals(artifactId)) {
                throw new IllegalArgumentException("artifactId must be a canonical UUID");
            }
            if (name == null
                    || name.isEmpty()
                    || name.length() > 255
                    || name.contains("/")
                    || name.contains("\\")
                    || name.chars().anyMatch(character -> character < 32 || character == 127)) {
                throw new IllegalArgumentException("artifact name must be a bounded file name");
            }
            if (mediaType == null || mediaType.length() > 128 || !mediaType.matches("[a-z0-9.+-]+/[a-z0-9.+-]+")) {
                throw new IllegalArgumentException("artifact mediaType must be a bounded media type");
            }
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("artifact sha256 must be 64 lowercase hex characters");
            }
            if (bytes < 0 || bytes > 128L * 1024 * 1024) {
                throw new IllegalArgumentException("artifact bytes must be within the worker's per-file bound");
            }
            if (expiresInSeconds < 1 || expiresInSeconds > 300) {
                throw new IllegalArgumentException("artifact expiry must be between 1 and 300 seconds");
            }
        }
    }

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
