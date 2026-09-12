package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.ProtocolValues.require;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the worker answered, read into a record whose constructor states what a valid answer is.
 *
 * <p>Only the answer's own properties belong here. Two kinds of check stay with the caller on
 * purpose: a value echoed back from the request is compared against what that caller sent, which a
 * constructor cannot see, and a failure code selects a branch rather than describing a malformed
 * answer. A record that tried to own either would have to be handed the caller's state.
 *
 * <p>Every rejection is an {@link IllegalArgumentException}. The worker is a machine, so a malformed
 * answer is transport failure: each caller remaps it to {@link WorkerUnavailableException}, which is
 * what tells the application never to infer that the command ran.
 */
final class WorkerResponses {

    /**
     * Reading is deliberately strict and deliberately local. The application's shared mapper is
     * configured for HTTP, and an answer from the worker must not change meaning because someone
     * later relaxes that configuration.
     */
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private WorkerResponses() {}

    static <T> T read(JsonNode response, Class<T> shape) {
        try {
            return JSON.treeToValue(response, shape);
        } catch (JacksonException malformed) {
            throw new IllegalArgumentException(shape.getSimpleName() + " is not a valid worker answer", malformed);
        }
    }

    /**
     * The handshake. Its protocol markers are pinned rather than compared to a range, because a
     * worker that advertises anything else is a different protocol and not a newer one; the
     * application refuses it instead of guessing which half still applies.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Handshake(
            boolean ok,
            int version,
            int maxFrameBytes,
            int codeActProtocol,
            int artifactProtocol,
            int moveProtocol,
            int exportProtocol,
            String workerBootId,
            int leaseSeconds,
            int renewAfterSeconds) {
        Handshake {
            require(ok, "ok", "must be true for a handshake");
            require(version == 1, "version", "must be 1");
            require(maxFrameBytes == WorkerClient.MAX_FRAME, "maxFrameBytes", "must be " + WorkerClient.MAX_FRAME);
            require(codeActProtocol == 1, "codeActProtocol", "must be 1");
            require(artifactProtocol == 1, "artifactProtocol", "must be 1");
            require(moveProtocol == 1, "moveProtocol", "must be 1");
            require(exportProtocol == 1, "exportProtocol", "must be 1");
            require(leaseSeconds >= 10 && leaseSeconds <= 3600, "leaseSeconds", "must be between 10 and 3600");
            // Renewing three times within one lease leaves room for two lost attempts.
            require(
                    renewAfterSeconds >= 1 && renewAfterSeconds <= leaseSeconds / 3,
                    "renewAfterSeconds",
                    "must be between 1 and a third of the lease");
        }

        UUID bootId() {
            return UUID.fromString(workerBootId);
        }
    }

    /**
     * One frozen capture. The identifier and the per-file fingerprints are the answer's own
     * properties and are checked here; whether the files are the ones this caller asked for is the
     * caller's comparison, because only it knows what it selected.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CaptureManifest(String captureId, List<CapturedFile> writes, List<String> deletes, List<String> absent) {
        /** The worker captures at most this many bytes across one command's selection. */
        static final long MAX_CAPTURED_BYTES = 4L * 1024 * 1024;

        CaptureManifest {
            captureId = ProtocolValues.uuid(captureId, "captureId");
            writes = writes == null ? List.of() : List.copyOf(writes);
            deletes = ProtocolValues.paths(deletes == null ? List.of() : deletes, 64, "deletes");
            absent = ProtocolValues.paths(absent == null ? List.of() : absent, 64, "absent");
            long total = 0;
            for (CapturedFile file : writes) {
                total += file.bytes();
            }
            require(total <= MAX_CAPTURED_BYTES, "captured bytes", "must not exceed " + MAX_CAPTURED_BYTES);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CapturedFile(String path, long bytes, String sha256) {
        CapturedFile {
            ProtocolValues.boundedText(path, 4096, "path");
            require(bytes >= 0, "bytes", "must not be negative");
            ProtocolValues.hex(sha256, 64, "sha256");
        }
    }

    /** One page of a capture. Its index and offset are echoes, so the caller compares them. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CaptureChunk(String captureId, int index, long offset, String data) {
        CaptureChunk {
            captureId = ProtocolValues.uuid(captureId, "captureId");
            require(index >= 0, "index", "must not be negative");
            require(offset >= 0, "offset", "must not be negative");
            require(data != null, "data", "must be present");
        }

        byte[] decoded() {
            try {
                return Base64.getDecoder().decode(data);
            } catch (IllegalArgumentException malformed) {
                throw new IllegalArgumentException("data must be base64", malformed);
            }
        }
    }

    /** A staged transfer the worker opened for this command. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Transfer(String transferId) {
        Transfer {
            transferId = ProtocolValues.uuid(transferId, "transferId");
        }
    }

    /**
     * How many bytes of a staged transfer the worker has. The caller compares it with what it has
     * sent, because a transfer that acknowledges a different total is not one it can continue.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransferProgress(long receivedBytes) {
        TransferProgress {
            require(receivedBytes >= 0, "receivedBytes", "must not be negative");
        }
    }

    /** A move's preflight. Anything but a ready plan leaves the repository untouched. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MovePreflight(Checked checked) {
        boolean ready() {
            return checked != null && checked.ready();
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Checked(boolean ready) {}
    }

    /**
     * A move the worker installed locally. The changed-path count is compared with the plan, so a
     * partial installation cannot be read as a complete one.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MoveInstallation(Installed installed) {
        MoveInstallation {
            require(installed != null, "installed", "must be present");
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Installed(int changedPaths, Boolean alreadyApplied) {
            Installed {
                require(changedPaths >= 0, "changedPaths", "must not be negative");
                require(alreadyApplied != null, "alreadyApplied", "must be present");
            }
        }
    }

    /**
     * One finished command. The combined preview bound and the agreement between {@code timedOut}
     * and the termination reason are properties of this answer and are checked here. The commit is
     * an echo of the pinned session commit, and the artifact maps carry their own richer metadata
     * rules, so both stay with the caller.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Execution(
            int exitCode,
            String stdout,
            String stderr,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            boolean timedOut,
            String terminationReason) {
        /** Two 16 KiB previews plus their worst-case multibyte expansion. */
        static final int MAX_PREVIEW_BYTES = 3 * 64 * 1024;

        Execution {
            require(stdout != null, "stdout", "must be present");
            require(stderr != null, "stderr", "must be present");
            int bytes = stdout.getBytes(StandardCharsets.UTF_8).length + stderr.getBytes(StandardCharsets.UTF_8).length;
            require(bytes <= MAX_PREVIEW_BYTES, "combined output", "must not exceed " + MAX_PREVIEW_BYTES + " bytes");
            require(terminationReason != null, "terminationReason", "must be present");
        }

        /**
         * The worker names two endings this application does not distinguish from cancellation,
         * and two it treats as the sandbox having failed. Anything else must name a reason this
         * application knows, or the answer is not one it can act on.
         */
        RepositoryExecutor.TerminationReason reason() {
            var reason =
                    switch (terminationReason) {
                        case "session_closed", "client_shutdown" -> RepositoryExecutor.TerminationReason.CANCELLED;
                        case "lease_expired", "sandbox_failed" -> RepositoryExecutor.TerminationReason.SANDBOX_FAILURE;
                        default ->
                            RepositoryExecutor.TerminationReason.valueOf(terminationReason.toUpperCase(Locale.ROOT));
                    };
            require(
                    timedOut == (reason == RepositoryExecutor.TerminationReason.TIMEOUT),
                    "timedOut",
                    "must agree with the termination reason");
            return reason;
        }
    }

    /**
     * A file the worker installed or deleted. A deletion reports a null digest, which is how the
     * caller tells an erased path from a written one.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Materialization(Installed installed) {
        Materialization {
            require(installed != null, "installed", "must be present");
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Installed(String path, String sha256) {
            Installed {
                ProtocolValues.boundedText(path, 4096, "path");
                if (sha256 != null) {
                    ProtocolValues.hex(sha256, 64, "sha256");
                }
            }
        }
    }
}
