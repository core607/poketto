package io.github.core607.poketto.executor.internal;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One bounded HTTP/1.1 exchange per Unix-socket connection: {@code GET /v1/hello} for the handshake and
 * {@code POST /v1/requests} for each signed request. EXEC is never replayed after an uncertain response.
 */
final class WorkerClient {
    private static final Logger log = LoggerFactory.getLogger(WorkerClient.class);
    /**
     * The body ceiling from the worker reference, applied to both directions. The handshake refuses
     * a worker that advertises anything else, because a larger body on one side would be refused by
     * the other.
     */
    static final int MAX_FRAME = 1024 * 1024;
    /** A snapshot archive: one 128 MiB binary file or 4 MiB of text, its manifest and tar framing. */
    static final long MAX_SNAPSHOT = 130L * 1024 * 1024;

    private final SandboxHttp http;
    private final PrivateKey signingKey;
    private final ObjectMapper json;
    private final Clock clock;
    private final UUID appBoot = UUID.randomUUID();

    WorkerClient(
            Path socket,
            PrivateKey signingKey,
            Runnable verifySocket,
            Consumer<SocketChannel> verifyPeer,
            ObjectMapper json,
            Clock clock) {
        this.http = new SandboxHttp(socket, verifySocket, verifyPeer);
        this.signingKey = signingKey;
        this.json = json;
        this.clock = clock;
    }

    Hello hello() {
        JsonNode response = exchange("GET", "/v1/hello", null, Duration.ofSeconds(3));
        try {
            var handshake = WorkerResponses.read(response, WorkerResponses.Handshake.class);
            return new Hello(handshake.bootId(), handshake.leaseSeconds(), handshake.renewAfterSeconds());
        } catch (WorkerUnavailableException rejected) {
            log.warn("Worker handshake rejected", rejected);
            throw rejected;
        } catch (RuntimeException exception) {
            log.warn("Worker handshake rejected", exception);
            throw new WorkerUnavailableException(exception);
        }
    }

    JsonNode request(Hello hello, Identity identity, String operation, WorkerRequests.Data data, Duration timeout) {
        return send(prepare(hello, identity, operation, data), timeout);
    }

    PreparedRequest prepare(Hello hello, Identity identity, String operation, WorkerRequests.Data data) {
        return prepare(hello, identity, appBoot, operation, data);
    }

    UUID applicationBootId() {
        return appBoot;
    }

    /** Only containment can use a retained application identity; new work uses this client's boot ID. */
    JsonNode closeRetainedLease(Hello hello, Identity identity, UUID retainedAppBoot, String reason, Duration timeout) {
        return send(prepare(hello, identity, retainedAppBoot, "CLOSE", new WorkerRequests.Close(reason)), timeout);
    }

    private PreparedRequest prepare(
            Hello hello, Identity identity, UUID applicationBoot, String operation, WorkerRequests.Data data) {
        UUID requestId = UUID.randomUUID();
        long issued = clock.instant().getEpochSecond();
        var payload = new Payload(
                1,
                hello.workerBootId(),
                applicationBoot,
                operation,
                requestId,
                issued,
                issued + hello.leaseSeconds(),
                identity.principalId(),
                identity.accountId(),
                identity.workspaceId(),
                identity.serverSessionHash(),
                identity.leaseId(),
                data);
        var event = new WorkerRequestEvents.Preparation();
        event.operation = operation;
        event.requestType = data == null ? "null" : data.getClass().getSimpleName();
        event.begin();
        try {
            byte[] bytes = serialize(payload, event);
            event.payloadBytes = bytes.length;
            byte[] signature = sign(bytes, event);
            var encoding = Base64.getUrlEncoder().withoutPadding();
            var prepared = new PreparedRequest(
                    requestId, new Envelope(encoding.encodeToString(bytes), encoding.encodeToString(signature)));
            event.prepared = true;
            return prepared;
        } catch (GeneralSecurityException | RuntimeException exception) {
            log.warn(
                    "Worker request could not be signed ({})",
                    exception.getClass().getSimpleName());
            throw new WorkerUnavailableException(exception);
        } finally {
            event.end();
            event.commit();
        }
    }

    private byte[] serialize(Payload payload, WorkerRequestEvents.Preparation event) {
        long started = System.nanoTime();
        try {
            return json.writeValueAsBytes(payload);
        } finally {
            event.serializationNanos = System.nanoTime() - started;
        }
    }

    private byte[] sign(byte[] bytes, WorkerRequestEvents.Preparation event) throws GeneralSecurityException {
        long started = System.nanoTime();
        try {
            return WorkerSignatures.sign(signingKey, bytes);
        } finally {
            event.signingNanos = System.nanoTime() - started;
        }
    }

    JsonNode send(PreparedRequest request, Duration timeout) {
        JsonNode response = exchange("POST", "/v1/requests", json.writeValueAsBytes(request.envelope()), timeout);
        require(response.path("requestId")
                .asString("")
                .equals(request.requestId().toString()));
        return response;
    }

    record PreparedRequest(UUID requestId, Envelope envelope) {}

    /** The signed wrapper. Its two fields are the complete body; the worker rejects any other key. */
    record Envelope(String payload, String signature) {}

    /**
     * The signed request. The worker compares this field set exactly, so every component below is
     * required and no component may be added without changing the worker.
     */
    record Payload(
            int version,
            UUID workerBootId,
            UUID appBootId,
            String operation,
            UUID requestId,
            long issuedAt,
            long expiresAt,
            UUID principalId,
            UUID accountId,
            UUID workspaceId,
            String serverSessionHash,
            UUID leaseId,
            WorkerRequests.Data data) {}

    /** What a snapshot request returns: a JSON refusal, or the archive's manifest and files in order. */
    interface SnapshotReader<T> {
        T refused(JsonNode answer);

        T read(JsonNode manifest, SnapshotArchive archive) throws IOException;
    }

    /**
     * One {@code SNAPSHOT} on its own route. The answer arrives within {@code timeout}; after that
     * each read of the archive may wait {@code idle}. The reader may stop early, which closes the
     * connection and makes the worker release the capture.
     */
    <T> T snapshot(PreparedRequest request, Duration timeout, Duration idle, SnapshotReader<T> reader) {
        String id = request.requestId().toString();
        return exchange(
                "POST", "/v1/snapshots", json.writeValueAsBytes(request.envelope()), timeout, idle, (head, body) -> {
                    if (head.contentType().equals("application/json")) {
                        require(head.length() <= MAX_FRAME);
                        JsonNode answer = json.readTree(body.readNBytes((int) head.length()));
                        require(answer.path("requestId").asString("").equals(id));
                        return reader.refused(answer);
                    }
                    require(head.contentType().equals("application/x-tar") && head.length() <= MAX_SNAPSHOT);
                    var archive = new SnapshotArchive(body);
                    JsonNode manifest = archive.manifest(json);
                    require(manifest.path("requestId").asString("").equals(id));
                    return reader.read(manifest, archive);
                });
    }

    private JsonNode exchange(String method, String path, byte[] body, Duration timeout) {
        return exchange(method, path, body, timeout, Duration.ZERO, (head, input) -> {
            require(head.contentType().equals("application/json") && head.length() <= MAX_FRAME);
            byte[] bytes = input.readNBytes((int) head.length());
            require(bytes.length == head.length());
            return json.readTree(bytes);
        });
    }

    private <T> T exchange(
            String method, String path, byte[] body, Duration timeout, Duration idle, SandboxHttp.Reader<T> reader) {
        return http.exchange(method, path, "application/json", body, timeout, idle, (head, input) -> {
            require(head.status() == 200 && head.length() > 0);
            return reader.read(head, input);
        });
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new WorkerUnavailableException();
        }
    }

    record Hello(UUID workerBootId, int leaseSeconds, int renewAfterSeconds) {}

    record Identity(UUID principalId, UUID accountId, UUID workspaceId, String serverSessionHash, UUID leaseId) {}
}
