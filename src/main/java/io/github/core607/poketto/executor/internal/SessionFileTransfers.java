package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.SessionWorker.hash;
import static io.github.core607.poketto.executor.internal.SessionWorker.requireOk;

import io.github.core607.poketto.content.ContentLimits;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/** Captures frozen worker files and installs host bytes with bounded, verified transfers. */
final class SessionFileTransfers {
    private static final Logger log = LoggerFactory.getLogger(SessionFileTransfers.class);
    private final SessionWorker io;

    SessionFileTransfers(SessionWorker io) {
        this.io = io;
    }

    WorkspaceSynchronization.Local captureSyncFile(ExecutionSession session, String executionId, String path) {
        JsonNode manifest = io.request(
                session, "CAPTURE_BINARY", new WorkerRequests.CapturePath(executionId, path), Duration.ofSeconds(8));
        if (manifest.path("code").asString("").equals("CAPTURE_REJECTED")) {
            String reason = manifest.path("reason").asString("");
            if (reason.equals("NOT_FOUND")) {
                return new WorkspaceSynchronization.Local(null, null, false);
            }
            if (reason.equals("NOT_REGULAR_FILE") || reason.equals("BINARY_LIMIT")) {
                return new WorkspaceSynchronization.Local(null, null, true);
            }
            throw InvalidSelectionException.capture(reason);
        }
        requireOk(manifest, session);
        var captured = WorkerResponses.read(manifest, WorkerResponses.BinaryCaptureManifest.class);
        try {
            var file = captured.writes().getFirst();
            if (!file.path().equals(path)) {
                throw new WorkerUnavailableException();
            }
            String text = file.bytes() <= ContentLimits.MAX_DOCUMENT_BYTES
                    ? capturedSyncText(session, executionId, captured.captureId(), file)
                    : null;
            return new WorkspaceSynchronization.Local(file.sha256(), text, false);
        } finally {
            requireOk(
                    io.request(
                            session,
                            "CAPTURE_RELEASE",
                            new WorkerRequests.CaptureRelease(executionId, captured.captureId()),
                            Duration.ofSeconds(3)),
                    session);
        }
    }

    private String capturedSyncText(
            ExecutionSession session, String executionId, String captureId, WorkerResponses.CapturedFile file) {
        if (file.bytes() == 0) {
            if (!file.sha256().equals(hash(""))) {
                throw new WorkerUnavailableException();
            }
            return "";
        }
        try (var input = new CapturedBinaryInput(file.bytes(), file.sha256(), (offset, limit) -> {
            io.authorize(session);
            JsonNode chunk = io.request(
                    session,
                    "CAPTURE_READ",
                    new WorkerRequests.CaptureRead(executionId, captureId, 0, offset, limit),
                    Duration.ofSeconds(3));
            requireOk(chunk, session);
            var page = WorkerResponses.read(chunk, WorkerResponses.CaptureChunk.class);
            if (!captureId.equals(page.captureId()) || page.index() != 0 || page.offset() != offset) {
                throw new WorkerUnavailableException();
            }
            return page.decoded();
        })) {
            byte[] bytes = input.readNBytes((int) file.bytes());
            try {
                String text = StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
                return text.indexOf('\0') < 0 ? text : null;
            } catch (CharacterCodingException binary) {
                return null;
            }
        } catch (IOException failure) {
            throw new WorkerUnavailableException(failure);
        }
    }

    Map<String, String> capture(
            ExecutionSession session, String executionId, List<String> writes, List<String> deletes) {
        JsonNode manifest = io.request(
                session,
                "CAPTURE_BEGIN",
                new WorkerRequests.CaptureBegin(executionId, writes, deletes),
                Duration.ofSeconds(5));
        return readCapture(session, executionId, manifest, writes, deletes);
    }

    private Map<String, String> readCapture(
            ExecutionSession session,
            String executionId,
            JsonNode manifest,
            List<String> writes,
            List<String> deletes) {
        if (manifest.path("code").asString("").equals("CAPTURE_REJECTED")) {
            throw InvalidSelectionException.capture(manifest.path("reason").asString(""));
        }
        requireOk(manifest, session);
        var captured = WorkerResponses.read(manifest, WorkerResponses.CaptureManifest.class);
        String captureId = captured.captureId();
        var reference = new WorkerRequests.CaptureRelease(executionId, captureId);
        try {
            // The manifest is well formed by construction; these compare it against this request.
            if (captured.writes().size() != writes.size() || !captured.deletes().equals(deletes)) {
                throw new WorkerUnavailableException();
            }
            var result = new LinkedHashMap<String, String>();
            for (int index = 0; index < captured.writes().size(); index++) {
                var file = captured.writes().get(index);
                String path = file.path();
                if (!path.equals(writes.get(index)) || result.containsKey(path)) {
                    throw new WorkerUnavailableException();
                }
                result.put(path, readCapturedText(session, executionId, captureId, index, file));
            }
            return result;
        } finally {
            requireOk(io.request(session, "CAPTURE_RELEASE", reference, Duration.ofSeconds(3)), session);
        }
    }

    private String readCapturedText(
            ExecutionSession session,
            String executionId,
            String captureId,
            int index,
            WorkerResponses.CapturedFile file) {
        long size = file.bytes();
        var bytes = new ByteArrayOutputStream((int) size);
        while (bytes.size() < size) {
            io.authorize(session);
            int limit = (int) Math.min(65536, size - bytes.size());
            JsonNode chunk = io.request(
                    session,
                    "CAPTURE_READ",
                    new WorkerRequests.CaptureRead(executionId, captureId, index, bytes.size(), limit),
                    Duration.ofSeconds(3));
            requireOk(chunk, session);
            var page = WorkerResponses.read(chunk, WorkerResponses.CaptureChunk.class);
            if (!captureId.equals(page.captureId()) || page.index() != index || page.offset() != bytes.size()) {
                throw new WorkerUnavailableException();
            }
            byte[] block = page.decoded();
            if (block.length != limit) {
                throw new WorkerUnavailableException();
            }
            bytes.writeBytes(block);
        }
        byte[] content = bytes.toByteArray();
        if (!hash(content).equals(file.sha256())) {
            throw new WorkerUnavailableException();
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException invalid) {
            throw new WorkerUnavailableException(invalid);
        }
    }

    Optional<String> captureOptional(ExecutionSession session, String executionId, String path) {
        JsonNode manifest = io.request(
                session, "CAPTURE_OPTIONAL", new WorkerRequests.CapturePath(executionId, path), Duration.ofSeconds(5));
        if (manifest.path("code").asString("").equals("CAPTURE_REJECTED")) {
            throw InvalidSelectionException.capture(manifest.path("reason").asString(""));
        }
        requireOk(manifest, session);
        List<String> absent = WorkerResponses.read(manifest, WorkerResponses.CaptureManifest.class)
                .absent();
        if (!absent.isEmpty() && !absent.equals(List.of(path))) {
            throw new WorkerUnavailableException();
        }
        return Optional.ofNullable(
                readCapture(session, executionId, manifest, absent.isEmpty() ? List.of(path) : List.of(), List.of())
                        .get(path));
    }

    boolean materialize(
            ExecutionSession session,
            String executionId,
            String path,
            long size,
            String digest,
            String expected,
            boolean delete,
            boolean allowIdentical,
            FileSource source) {
        var metadata =
                new WorkerRequests.MaterializeBegin(executionId, path, size, digest, expected, delete, allowIdentical);
        JsonNode begun = io.request(session, "MATERIALIZE_BEGIN", metadata, Duration.ofSeconds(3));
        checkMaterialization(begun);
        if (begun.path("code").asString("").equals("MATERIALIZE_REJECTED")) {
            throw new IllegalArgumentException("the worker refused to stage the outgoing file");
        }
        requireOk(begun, session);
        String transferId =
                WorkerResponses.read(begun, WorkerResponses.Transfer.class).transferId();
        var reference = new WorkerRequests.Transfer(executionId, transferId);
        try {
            var sink = new MaterializationOutput(session, executionId, transferId, size);
            var output = new BufferedOutputStream(sink, 65536);
            // Flush only after the source succeeds. Closing on failure could replay a buffered chunk.
            source.writeTo(output);
            output.flush();
            if (sink.sent != size) {
                throw new IllegalArgumentException("media source is incomplete");
            }
            io.authorize(session);
            JsonNode committed = io.request(session, "MATERIALIZE_COMMIT", reference, Duration.ofSeconds(5));
            if (committed.path("code").asString("").equals("MATERIALIZE_REJECTED")) {
                return false;
            }
            checkMaterialization(committed);
            requireOk(committed, session);
            var installed = WorkerResponses.read(committed, WorkerResponses.Materialization.class)
                    .installed();
            // A deletion is acknowledged by an explicit null digest. An absent key is a malformed
            // answer and must not be read as a file that was erased.
            JsonNode reported = committed.path("installed").path("sha256");
            if (!installed.path().equals(path) || (delete ? !reported.isNull() : !digest.equals(installed.sha256()))) {
                throw new WorkerUnavailableException();
            }
            return true;
        } catch (IOException failure) {
            throw new WorkerUnavailableException(failure);
        } finally {
            try {
                io.request(session, "MATERIALIZE_ABORT", reference, Duration.ofSeconds(3));
            } catch (RuntimeException cleanupFailure) {
                log.warn(
                        "Worker transfer cleanup was not acknowledged; command cleanup will release its slot",
                        cleanupFailure);
            }
        }
    }

    private final class MaterializationOutput extends OutputStream {
        private final ExecutionSession session;
        private final String executionId;
        private final String transferId;
        private final long size;
        private long sent;

        MaterializationOutput(ExecutionSession session, String executionId, String transferId, long size) {
            this.session = session;
            this.executionId = executionId;
            this.transferId = transferId;
            this.size = size;
        }

        @Override
        public void write(int value) {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length > size - sent) {
                throw new IllegalArgumentException("media source exceeds its declared size");
            }
            while (length > 0) {
                io.authorize(session);
                int count = Math.min(65536, length);
                JsonNode chunk = io.request(
                        session,
                        "MATERIALIZE_CHUNK",
                        new WorkerRequests.TransferChunk(
                                executionId,
                                transferId,
                                sent,
                                Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes, offset, offset + count))),
                        Duration.ofSeconds(3));
                checkMaterialization(chunk);
                requireOk(chunk, session);
                sent += count;
                if (WorkerResponses.read(chunk, WorkerResponses.TransferProgress.class)
                                .receivedBytes()
                        != sent) {
                    throw new WorkerUnavailableException();
                }
                offset += count;
                length -= count;
            }
        }
    }

    private static void checkMaterialization(JsonNode response) {
        String code = response.path("code").asString("");
        if (code.equals("MATERIALIZE_CAPACITY")) {
            throw new MaterializationCapacity();
        }
    }

    @FunctionalInterface
    interface FileSource {
        void writeTo(OutputStream output) throws IOException;
    }

    static final class MaterializationCapacity extends RuntimeException {
        MaterializationCapacity() {
            super("Insufficient worker materialization capacity");
        }
    }
}
