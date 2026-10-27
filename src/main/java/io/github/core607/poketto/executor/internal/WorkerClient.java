package io.github.core607.poketto.executor.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
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
    /** The response status line and headers together. */
    private static final int MAX_HEAD = 8192;
    /** A snapshot archive: one 128 MiB binary file or 4 MiB of text, its manifest and tar framing. */
    static final long MAX_SNAPSHOT = 130L * 1024 * 1024;

    private final Path socket;
    private final PrivateKey signingKey;
    private final Runnable verifySocket;
    private final Consumer<SocketChannel> verifyPeer;
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
        this.socket = socket;
        this.signingKey = signingKey;
        this.verifySocket = verifySocket;
        this.verifyPeer = verifyPeer;
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

    Hello gameHello() {
        JsonNode response = exchange("GET", "/v1/hello", null, Duration.ofSeconds(3));
        var handshake = WorkerResponses.read(response, IsolatedGameRunner.Handshake.class);
        return new Hello(handshake.workerBootId(), handshake.leaseSeconds(), handshake.renewAfterSeconds());
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

    /** The response head: its media type and declared body length; only a {@code 200} is accepted. */
    private record Head(String contentType, long length) {}

    @FunctionalInterface
    private interface Body<T> {
        T read(Head head, InputStream body) throws IOException;
    }

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

    private <T> T exchange(String method, String path, byte[] body, Duration timeout, Duration idle, Body<T> reader) {
        verifySocket.run();
        ByteBuffer output = request(method, path, body == null ? new byte[0] : body);
        long deadline = System.nanoTime() + timeout.toNanos();
        String phase = "connect";
        boolean reading = false;
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                Selector selector = Selector.open()) {
            channel.configureBlocking(false);
            if (!channel.connect(UnixDomainSocketAddress.of(socket))) {
                while (!channel.finishConnect()) {
                    ready(channel, selector, SelectionKey.OP_CONNECT, deadline);
                }
            }
            phase = "peer-verification";
            verifyPeer.accept(channel);
            phase = "request-write";
            while (output.hasRemaining()) {
                deadline(deadline);
                if (channel.write(output) == 0) {
                    ready(channel, selector, SelectionKey.OP_WRITE, deadline);
                }
            }
            phase = "response-header";
            Response response = response(channel, selector, deadline, idle.toNanos());
            phase = "response-body";
            reading = true;
            return reader.read(response.head(), response.body());
        } catch (IOException | WorkerUnavailableException exception) {
            throw unavailable(phase, exception);
        } catch (RuntimeException exception) {
            if (reading) {
                // A reader's own refusal, such as an invalid selection, keeps its meaning.
                throw exception;
            }
            throw unavailable(phase, exception);
        }
    }

    private static WorkerUnavailableException unavailable(String phase, Exception exception) {
        log.warn(
                "Worker exchange failed during {} ({})",
                phase,
                exception.getClass().getSimpleName());
        return exception instanceof WorkerUnavailableException unavailable
                ? unavailable
                : new WorkerUnavailableException(exception);
    }

    private static ByteBuffer request(String method, String path, byte[] body) {
        require(body.length <= MAX_FRAME);
        byte[] head = (method + " " + path + " HTTP/1.1\r\nHost: sandcrab\r\n"
                        + (body.length > 0
                                ? "Content-Type: application/json\r\nContent-Length: " + body.length + "\r\n"
                                : "")
                        + "Connection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(head.length + body.length)
                .put(head)
                .put(body)
                .flip();
    }

    private record Response(Head head, InputStream body) {}

    /** The response head and its declared body, beginning with the bytes that arrived with the head. */
    private static Response response(SocketChannel channel, Selector selector, long deadline, long idle)
            throws IOException {
        ByteBuffer head = ByteBuffer.allocate(MAX_HEAD + 4);
        int end;
        while ((end = headEnd(head)) < 0) {
            require(head.hasRemaining());
            var input = new ChannelInput(channel, selector, deadline, 0, head.remaining(), null, 0, 0);
            int read = input.read(head.array(), head.position(), head.remaining());
            if (read < 0) {
                throw new IOException("incomplete worker response");
            }
            head.position(head.position() + read);
        }
        Head parsed = head(new String(head.array(), 0, end, StandardCharsets.US_ASCII));
        int received = head.position() - end - 4;
        require(received <= parsed.length());
        return new Response(
                parsed,
                new ChannelInput(channel, selector, deadline, idle, parsed.length(), head.array(), end + 4, received));
    }

    /** The offset of the blank line ending the response head, or -1 before it has arrived. */
    private static int headEnd(ByteBuffer response) {
        byte[] bytes = response.array();
        for (int index = 0; index + 3 < response.position(); index++) {
            if (bytes[index] == '\r'
                    && bytes[index + 1] == '\n'
                    && bytes[index + 2] == '\r'
                    && bytes[index + 3] == '\n') {
                return index;
            }
        }
        return -1;
    }

    /** A {@code 200} status with one media type and exactly one {@code Content-Length}. */
    private static Head head(String head) {
        String[] lines = head.split("\r\n", -1);
        require(lines[0].startsWith("HTTP/1.1 200 "));
        Long length = null;
        String type = null;
        for (int index = 1; index < lines.length; index++) {
            int colon = lines[index].indexOf(':');
            require(colon > 0);
            String name = lines[index].substring(0, colon);
            String value = lines[index].substring(colon + 1).strip();
            if (name.equalsIgnoreCase("Content-Length")) {
                require(length == null
                        && !value.isEmpty()
                        && value.length() <= 12
                        && value.chars().allMatch(Character::isDigit));
                length = Long.parseLong(value);
            } else if (name.equalsIgnoreCase("Content-Type")) {
                require(type == null);
                type = value;
            } else {
                require(!name.equalsIgnoreCase("Transfer-Encoding"));
            }
        }
        require(length != null && length > 0 && type != null);
        return new Head(type, length);
    }

    /**
     * The declared body: first the bytes that arrived with the head, then the socket. Each read
     * waits until the deadline, which a positive {@code idle} moves forward after every read.
     */
    private static final class ChannelInput extends InputStream {
        private final SocketChannel channel;
        private final Selector selector;
        private final long idle;
        private final byte[] prefix;
        private long deadline;
        private long remaining;
        private int prefixOffset;
        private int prefixLeft;

        ChannelInput(
                SocketChannel channel,
                Selector selector,
                long deadline,
                long idle,
                long remaining,
                byte[] prefix,
                int prefixOffset,
                int prefixLeft) {
            this.channel = channel;
            this.selector = selector;
            this.deadline = deadline;
            this.idle = idle;
            this.remaining = remaining;
            this.prefix = prefix;
            this.prefixOffset = prefixOffset;
            this.prefixLeft = prefixLeft;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining == 0) {
                return -1;
            }
            if (length == 0) {
                return 0;
            }
            int wanted = (int) Math.min(length, remaining);
            int read;
            if (prefixLeft > 0) {
                read = Math.min(wanted, prefixLeft);
                System.arraycopy(prefix, prefixOffset, bytes, offset, read);
                prefixOffset += read;
                prefixLeft -= read;
            } else {
                ByteBuffer target = ByteBuffer.wrap(bytes, offset, wanted);
                while (true) {
                    deadline(deadline);
                    read = channel.read(target);
                    if (read < 0) {
                        throw new IOException("incomplete worker response");
                    }
                    if (read > 0) {
                        break;
                    }
                    ready(channel, selector, SelectionKey.OP_READ, deadline);
                }
                if (idle > 0) {
                    deadline = System.nanoTime() + idle;
                }
            }
            remaining -= read;
            return read;
        }
    }

    private static void ready(SocketChannel channel, Selector selector, int operation, long deadline)
            throws IOException {
        deadline(deadline);
        channel.register(selector, operation);
        long millis = Math.max(1, Math.min(1000, (deadline - System.nanoTime()) / 1_000_000));
        selector.select(millis);
        selector.selectedKeys().clear();
    }

    private static void deadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0) {
            throw new IOException("worker exchange deadline elapsed");
        }
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new WorkerUnavailableException();
        }
    }

    record Hello(UUID workerBootId, int leaseSeconds, int renewAfterSeconds) {}

    record Identity(UUID principalId, UUID accountId, UUID workspaceId, String serverSessionHash, UUID leaseId) {}
}
