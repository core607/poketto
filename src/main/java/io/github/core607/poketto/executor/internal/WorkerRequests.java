package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.ProtocolValues.boundedText;
import static io.github.core607.poketto.executor.internal.ProtocolValues.hex;
import static io.github.core607.poketto.executor.internal.ProtocolValues.inRange;
import static io.github.core607.poketto.executor.internal.ProtocolValues.paths;
import static io.github.core607.poketto.executor.internal.ProtocolValues.require;
import static io.github.core607.poketto.executor.internal.ProtocolValues.uuid;
import static io.github.core607.poketto.executor.internal.ProtocolValues.withoutNul;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code data} object of each worker operation, one record per operation, validated on
 * construction. The worker compares the received field set against a fixed set per operation and
 * rejects any difference, so a record's components are exactly that set: adding, removing, or
 * renaming one changes the wire contract. The worker reference owns the authoritative table.
 *
 * <p>Field order never reaches the worker, which compares sets. Null does: {@link MaterializeBegin}
 * keeps {@code expectedSha256} in the frame even when it is null, because the worker requires that
 * key to be present and reads null as "no precondition". Serialization therefore has to include
 * nulls, which is Jackson's default and which {@code WorkerRequestFrameTests} pins.
 */
final class WorkerRequests {

    private WorkerRequests() {}

    /**
     * One signed operation's payload. The operation name stays a separate argument because several
     * operations share a payload shape: five transfer operations carry only their slot, and two
     * capture operations carry only a path.
     */
    sealed interface Data
            permits ArtifactCreate,
                    ArtifactRead,
                    ArtifactRemove,
                    BridgeComplete,
                    BridgePoll,
                    CaptureBegin,
                    CapturePath,
                    CaptureRead,
                    CaptureRelease,
                    Close,
                    Exec,
                    MaterializeBegin,
                    MoveBegin,
                    Open,
                    Renew,
                    Revoke,
                    Transfer,
                    TransferChunk {}

    /** Identifies a running command. Every operation except the lease and artifact ones carries one. */
    private static String execution(String executionId) {
        return uuid(executionId, "executionId");
    }

    /**
     * The unsigned handshake, the only request not wrapped in a signed envelope and the only one
     * carrying its own operation name.
     */
    record Hello(String operation, int version) {
        Hello() {
            this("HELLO", 1);
        }
    }

    record Open(UUID exportId, String bundleSha256, long bundleBytes, String commit) implements Data {
        Open {
            require(exportId != null, "exportId", "must be present");
            hex(bundleSha256, 64, "bundleSha256");
            require(bundleBytes > 0, "bundleBytes", "must be positive");
            hex(commit, 40, "commit");
        }
    }

    record Exec(String executionId, String commit, String command, long timeoutMillis) implements Data {
        Exec {
            executionId = execution(executionId);
            hex(commit, 40, "commit");
            withoutNul(boundedText(command, 64 * 1024, "command"), "command");
            require(timeoutMillis > 0, "timeoutMillis", "must be positive");
        }
    }

    /** Extends the lease. The worker rejects any field, so this record carries none. */
    record Renew() implements Data {}

    /** Claims one pending bridge request. Like {@link Renew}, it carries no field. */
    record BridgePoll() implements Data {}

    record BridgeComplete(String executionId, String bridgeRequestId, Object response) implements Data {
        BridgeComplete {
            executionId = execution(executionId);
            bridgeRequestId = uuid(bridgeRequestId, "bridgeRequestId");
            require(response != null, "response", "must be present");
        }
    }

    /**
     * Ends the lease. The protocol also accepts an empty object, but this client always states why
     * it is closing, so the reason is required here rather than conditionally serialized.
     */
    record Close(String reason) implements Data {
        private static final List<String> REASONS = List.of("cancelled", "session_closed", "client_shutdown");

        Close {
            require(REASONS.contains(reason), "reason", "must be one of " + REASONS);
        }
    }

    record Revoke(Set<UUID> keyIds, Set<UUID> accountIds) implements Data {
        Revoke {
            require(keyIds != null, "keyIds", "must be present");
            require(accountIds != null, "accountIds", "must be present");
            require(keyIds.size() <= 1000, "keyIds", "must not exceed 1000 entries");
            require(accountIds.size() <= 1000, "accountIds", "must not exceed 1000 entries");
            keyIds = Set.copyOf(keyIds);
            accountIds = Set.copyOf(accountIds);
        }
    }

    record CaptureBegin(String executionId, List<String> writes, List<String> deletes) implements Data {
        CaptureBegin {
            executionId = execution(executionId);
            writes = paths(writes, 64, "writes");
            deletes = paths(deletes, 64, "deletes");
        }
    }

    /** Shared by {@code CAPTURE_OPTIONAL} and {@code CAPTURE_BINARY}, which take the same two fields. */
    record CapturePath(String executionId, String path) implements Data {
        CapturePath {
            executionId = execution(executionId);
            boundedText(path, 4096, "path");
        }
    }

    record CaptureRead(String executionId, String captureId, int index, long offset, int limit) implements Data {
        CaptureRead {
            executionId = execution(executionId);
            captureId = uuid(captureId, "captureId");
            require(index >= 0, "index", "must not be negative");
            require(offset >= 0, "offset", "must not be negative");
            inRange(limit, 1, 65536, "limit");
        }
    }

    record CaptureRelease(String executionId, String captureId) implements Data {
        CaptureRelease {
            executionId = execution(executionId);
            captureId = uuid(captureId, "captureId");
        }
    }

    record MaterializeBegin(
            String executionId,
            String path,
            long bytes,
            String sha256,
            String expectedSha256,
            boolean delete,
            boolean allowIdentical)
            implements Data {
        MaterializeBegin {
            executionId = execution(executionId);
            boundedText(path, 4096, "path");
            inRange(bytes, 0, 1024L * 1024 * 1024, "bytes");
            hex(sha256, 64, "sha256");
            if (expectedSha256 != null) {
                hex(expectedSha256, 64, "expectedSha256");
            }
        }
    }

    /** Shared by {@code MATERIALIZE_CHUNK} and {@code MOVE_CHUNK}; both stage the next exact offset. */
    record TransferChunk(String executionId, String transferId, long offset, String data) implements Data {
        TransferChunk {
            executionId = execution(executionId);
            transferId = uuid(transferId, "transferId");
            require(offset >= 0, "offset", "must not be negative");
            require(data != null, "data", "must be present");
        }
    }

    /**
     * Shared by every transfer operation that only names its slot: {@code MATERIALIZE_COMMIT},
     * {@code MATERIALIZE_ABORT}, {@code MOVE_CHECK}, {@code MOVE_COMMIT} and {@code MOVE_ABORT}.
     */
    record Transfer(String executionId, String transferId) implements Data {
        Transfer {
            executionId = execution(executionId);
            transferId = uuid(transferId, "transferId");
        }
    }

    record MoveBegin(String executionId, long bytes, String sha256) implements Data {
        MoveBegin {
            executionId = execution(executionId);
            inRange(bytes, 1, 64L * 1024 * 1024, "bytes");
            hex(sha256, 64, "sha256");
        }
    }

    record ArtifactCreate(String executionId, String path, String mediaType) implements Data {
        ArtifactCreate {
            executionId = execution(executionId);
            boundedText(path, 4096, "path");
            boundedText(mediaType, 128, "mediaType");
        }
    }

    record ArtifactRead(String artifactId, long offset, int limit) implements Data {
        ArtifactRead {
            artifactId = uuid(artifactId, "artifactId");
            require(offset >= 0, "offset", "must not be negative");
            inRange(limit, 1, 65536, "limit");
        }
    }

    record ArtifactRemove(String artifactId) implements Data {
        ArtifactRemove {
            artifactId = uuid(artifactId, "artifactId");
        }
    }
}
