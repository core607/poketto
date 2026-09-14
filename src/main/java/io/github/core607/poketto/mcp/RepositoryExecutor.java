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
    long MAX_GENERATION = 9_007_199_254_740_991L;

    record CopyRequest(String id, Long generation, boolean resume) {
        public CopyRequest {
            id = requireCopyId(id);
            if (generation != null && (generation < 1 || generation > MAX_GENERATION)) {
                throw new IllegalArgumentException("Expected generation must be a positive safe integer");
            }
            if (NEW_COPY.equals(id) && (generation != null || resume)) {
                throw new IllegalArgumentException("New copies cannot carry a generation or request recovery");
            }
            if (resume && generation == null) {
                throw new IllegalArgumentException("Explicit recovery requires the expected generation");
            }
        }
    }

    record CopyRetention(long generation, long expiresAt, boolean resumed, UUID lastInterruptedCommand) {
        public CopyRetention {
            if (generation < 1 || generation > MAX_GENERATION || expiresAt < 1) {
                throw new IllegalArgumentException("Retained copy requires a bounded generation and expiry");
            }
        }
    }

    record DiscardRequest(String id, Long generation) {
        public DiscardRequest {
            if (NEW_COPY.equals(requireCopyId(id))) {
                throw new IllegalArgumentException("Discard requires an existing copy ID");
            }
            if (generation != null && (generation < 1 || generation > MAX_GENERATION)) {
                throw new IllegalArgumentException("Discard requires a positive safe generation");
            }
        }
    }

    enum DiscardStatus {
        DISCARDED,
        ABSENT
    }

    record DiscardResult(String copyId, DiscardStatus status) {}

    /**
     * Discards only this subject's exact copy after containing its writer. Non-retained copies omit
     * generation; retained copies require their last observed generation. Current execution
     * permission is required, but no content is returned and private-read/publication grants need not
     * survive. Expired records may be discarded. Busy or stale writers prevent deletion. ABSENT is
     * idempotent and reveals no other owner's copy. Remote Git writes are never undone. Physical
     * checkpoint cleanup may finish later; an unconfirmed response permits retrying this exact request.
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
     * The session id comes from the server SDK, after principal/workspace binding validation.
     * expectedCopy.id is "new" only for explicit initial admission; otherwise it must match the
     * acknowledged working-copy ID. Retained copies also require their last observed generation.
     * Explicit resume transfers that exact copy before running this command in the same request.
     * A generation mismatch or admission refusal never executes this command; an earlier command
     * may still have partially completed. Retention metadata reports fixed expiry and interruption.
     * Omitted commits retain this execution session's pinned commit. Implementations own command,
     * output, process-tree, filesystem, network, cancellation, and lease limits.
     * Worker lifecycle synchronization must prevent process creation after cancellation and make
     * every registered termination callback stop the complete command process tree.
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
