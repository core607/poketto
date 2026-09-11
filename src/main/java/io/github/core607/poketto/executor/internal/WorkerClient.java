package io.github.core607.poketto.executor.internal;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** One bounded Unix-socket exchange per operation; EXEC is never replayed after an uncertain response. */
final class WorkerClient {
    private static final Logger log = LoggerFactory.getLogger(WorkerClient.class);
    static final int MAX_FRAME = 1024 * 1024;
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
        JsonNode response = exchange(new WorkerRequests.Hello(), Duration.ofSeconds(3));
        try {
            require(response.path("ok").booleanValue()
                    && response.path("version").intValue() == 1);
            require(response.path("maxFrameBytes").intValue() == MAX_FRAME);
            require(response.path("codeActProtocol").asInt(0) == 1);
            require(response.path("artifactProtocol").asInt(0) == 1);
            require(response.path("moveProtocol").asInt(0) == 1);
            require(response.path("exportProtocol").asInt(0) == 1);
            UUID boot = UUID.fromString(response.path("workerBootId").stringValue());
            int lease = response.path("leaseSeconds").intValue();
            int renew = response.path("renewAfterSeconds").intValue();
            require(lease >= 10 && lease <= 3600 && renew >= 1 && renew <= lease / 3);
            return new Hello(boot, lease, renew);
        } catch (RuntimeException exception) {
            log.warn("Worker handshake rejected ({})", exception.getClass().getSimpleName());
            throw new WorkerUnavailableException(exception);
        }
    }

    JsonNode request(Hello hello, Identity identity, String operation, WorkerRequests.Data data, Duration timeout) {
        return send(prepare(hello, identity, operation, data), timeout);
    }

    PreparedRequest prepare(Hello hello, Identity identity, String operation, WorkerRequests.Data data) {
        UUID requestId = UUID.randomUUID();
        long issued = clock.instant().getEpochSecond();
        var payload = new Payload(
                1,
                hello.workerBootId(),
                appBoot,
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
        try {
            byte[] bytes = json.writeValueAsBytes(payload);
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(signingKey);
            signer.update(bytes);
            var encoding = Base64.getUrlEncoder().withoutPadding();
            return new PreparedRequest(
                    requestId, new Envelope(encoding.encodeToString(bytes), encoding.encodeToString(signer.sign())));
        } catch (GeneralSecurityException | RuntimeException exception) {
            log.warn(
                    "Worker request could not be signed ({})",
                    exception.getClass().getSimpleName());
            throw new WorkerUnavailableException(exception);
        }
    }

    JsonNode send(PreparedRequest request, Duration timeout) {
        JsonNode response = exchange(request.envelope(), timeout);
        require(response.path("requestId")
                .asString("")
                .equals(request.requestId().toString()));
        return response;
    }

    record PreparedRequest(UUID requestId, Envelope envelope) {}

    /** The signed wrapper. Its two fields are the complete frame; the worker rejects any other key. */
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

    private JsonNode exchange(Object request, Duration timeout) {
        verifySocket.run();
        byte[] bytes = json.writeValueAsBytes(request);
        require(bytes.length > 0 && bytes.length <= MAX_FRAME);
        long deadline = System.nanoTime() + timeout.toNanos();
        String phase = "connect";
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
            ByteBuffer output = ByteBuffer.allocate(4 + bytes.length)
                    .putInt(bytes.length)
                    .put(bytes)
                    .flip();
            while (output.hasRemaining()) {
                deadline(deadline);
                if (channel.write(output) == 0) {
                    ready(channel, selector, SelectionKey.OP_WRITE, deadline);
                }
            }
            phase = "response-header";
            ByteBuffer prefix = ByteBuffer.allocate(4);
            read(channel, selector, prefix, deadline);
            int length = prefix.flip().getInt();
            require(length > 0 && length <= MAX_FRAME);
            phase = "response-body";
            ByteBuffer body = ByteBuffer.allocate(length);
            read(channel, selector, body, deadline);
            phase = "response-decode";
            return json.readTree(body.array());
        } catch (IOException | RuntimeException exception) {
            log.warn(
                    "Worker exchange failed during {} ({})",
                    phase,
                    exception.getClass().getSimpleName());
            throw new WorkerUnavailableException(exception);
        }
    }

    private static void read(SocketChannel channel, Selector selector, ByteBuffer body, long deadline)
            throws IOException {
        while (body.hasRemaining()) {
            deadline(deadline);
            int read = channel.read(body);
            if (read < 0) {
                throw new IOException("incomplete worker frame");
            }
            if (read == 0) {
                ready(channel, selector, SelectionKey.OP_READ, deadline);
            }
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
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
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
