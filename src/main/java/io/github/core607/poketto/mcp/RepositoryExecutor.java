package io.github.core607.poketto.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Execution boundary supplied only by a verified isolated worker; no ordinary subprocess fallback. */
public interface RepositoryExecutor {
    String NEW_COPY = "new";

    record CopyRequest(String id) {
        public CopyRequest {
            id = requireCopyId(id);
        }
    }

    record CopyRetention(long expiresAt, boolean resumed, UUID lastInterruptedCommand) {
        public CopyRetention {
            if (expiresAt < 1) {
                throw new IllegalArgumentException("Copy expiry must be positive");
            }
        }
    }

    record DiscardRequest(String id) {
        public DiscardRequest {
            if (NEW_COPY.equals(requireCopyId(id))) {
                throw new IllegalArgumentException("Discard requires an existing copy ID");
            }
        }
    }

    enum DiscardStatus {
        DISCARDED,
        ABSENT
    }

    record DiscardResult(String copyId, DiscardStatus status) {}

    /**
     * Discards the account's exact copy after containing its writer. Current execution permission is
     * required; no content is returned and private-read/publication grants need not survive.
     * Busy writers prevent deletion. ABSENT is idempotent and reveals no other account's copy.
     * Remote Git writes are never undone. Retry an unconfirmed discard with the same copy ID.
     */
    DiscardResult discard(
            AuthPrincipal principal, WorkspaceId workspace, DiscardRequest request, ExecutionCancellation cancellation);

    static String requireCopyId(String value) {
        if (NEW_COPY.equals(value)) {
            return value;
        }
        if (value == null || !UUID.fromString(value).toString().equals(value)) {
            throw new IllegalArgumentException("Use new or a canonical working-copy ID");
        }
        return value;
    }

    /**
     * The transport ID comes from the server SDK after principal/workspace validation. The account
     * and workspace own the copy; transports do not own its lifetime. "new" opens the default copy,
     * creating it only when absent. An explicit ID must match before this command can execute.
     * Reconnection restores the original baseline and local work automatically. Retention reports
     * renewed expiry and any earlier interrupted command; inspect uncertain writes before retrying.
     * Omitted commits keep the pinned baseline. Implementations own process-tree, filesystem,
     * network, cancellation, output and resource limits, and prevent process creation after cancellation.
     */
    ExecutionResult execute(
            AuthPrincipal principal,
            WorkspaceId workspace,
            String serverSessionId,
            CopyRequest expectedCopy,
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
            String copyId,
            String commit,
            int exitCode,
            String stdout,
            String stderr,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            boolean timedOut,
            TerminationReason terminationReason,
            Map<String, ArtifactMetadata> artifacts,
            Map<String, String> artifactErrors,
            @JsonInclude(JsonInclude.Include.NON_NULL) CopyRetention retention) {
        public ExecutionResult {
            if (NEW_COPY.equals(requireCopyId(copyId))) {
                throw new IllegalArgumentException("Result requires a copy ID");
            }
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
