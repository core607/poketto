package io.github.core607.poketto.executor.internal;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** One bounded Unix-socket exchange per operation; EXEC is never replayed after an uncertain response. */
final class WorkerClient {
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
        JsonNode response = exchange(Map.of("operation", "HELLO", "version", 1), Duration.ofSeconds(3));
        try {
            require(response.path("ok").booleanValue()
                    && response.path("version").intValue() == 1);
            require(response.path("maxFrameBytes").intValue() == MAX_FRAME);
            UUID boot = UUID.fromString(response.path("workerBootId").stringValue());
            int lease = response.path("leaseSeconds").intValue();
            int renew = response.path("renewAfterSeconds").intValue();
            require(lease >= 10 && lease <= 3600 && renew >= 1 && renew <= lease / 3);
            return new Hello(boot, lease, renew);
        } catch (RuntimeException exception) {
            throw new WorkerUnavailableException();
        }
    }

    JsonNode request(Hello hello, Identity identity, String operation, Map<String, ?> data, Duration timeout) {
        return send(prepare(hello, identity, operation, data), timeout);
    }

    PreparedRequest prepare(Hello hello, Identity identity, String operation, Map<String, ?> data) {
        UUID requestId = UUID.randomUUID();
        long issued = clock.instant().getEpochSecond();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", 1);
        payload.put("workerBootId", hello.workerBootId());
        payload.put("appBootId", appBoot);
        payload.put("operation", operation);
        payload.put("requestId", requestId);
        payload.put("issuedAt", issued);
        payload.put("expiresAt", issued + hello.leaseSeconds());
        payload.put("principalId", identity.principalId());
        payload.put("accountId", identity.accountId());
        payload.put("workspaceId", identity.workspaceId());
        payload.put("serverSessionHash", identity.serverSessionHash());
        payload.put("leaseId", identity.leaseId());
        payload.put("data", data);
        try {
            byte[] bytes = json.writeValueAsBytes(payload);
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(signingKey);
            signer.update(bytes);
            var encoding = Base64.getUrlEncoder().withoutPadding();
            return new PreparedRequest(
                    requestId,
                    Map.of(
                            "payload",
                            encoding.encodeToString(bytes),
                            "signature",
                            encoding.encodeToString(signer.sign())));
        } catch (Exception exception) {
            throw new WorkerUnavailableException();
        }
    }

    JsonNode send(PreparedRequest request, Duration timeout) {
        JsonNode response = exchange(request.envelope(), timeout);
        require(response.path("requestId")
                .asString("")
                .equals(request.requestId().toString()));
        return response;
    }

    record PreparedRequest(UUID requestId, Map<String, String> envelope) {}

    private JsonNode exchange(Object request, Duration timeout) {
        verifySocket.run();
        byte[] bytes = json.writeValueAsBytes(request);
        require(bytes.length > 0 && bytes.length <= MAX_FRAME);
        long deadline = System.nanoTime() + timeout.toNanos();
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                Selector selector = Selector.open()) {
            channel.configureBlocking(false);
            if (!channel.connect(UnixDomainSocketAddress.of(socket))) {
                while (!channel.finishConnect()) ready(channel, selector, SelectionKey.OP_CONNECT, deadline);
            }
            verifyPeer.accept(channel);
            ByteBuffer output = ByteBuffer.allocate(4 + bytes.length)
                    .putInt(bytes.length)
                    .put(bytes)
                    .flip();
            while (output.hasRemaining()) {
                deadline(deadline);
                if (channel.write(output) == 0) ready(channel, selector, SelectionKey.OP_WRITE, deadline);
            }
            ByteBuffer prefix = ByteBuffer.allocate(4);
            read(channel, selector, prefix, deadline);
            int length = prefix.flip().getInt();
            require(length > 0 && length <= MAX_FRAME);
            ByteBuffer body = ByteBuffer.allocate(length);
            read(channel, selector, body, deadline);
            return json.readTree(body.array());
        } catch (IOException | RuntimeException exception) {
            throw new WorkerUnavailableException();
        }
    }

    private static void read(SocketChannel channel, Selector selector, ByteBuffer body, long deadline)
            throws IOException {
        while (body.hasRemaining()) {
            deadline(deadline);
            int read = channel.read(body);
            if (read < 0) throw new IOException("incomplete worker frame");
            if (read == 0) ready(channel, selector, SelectionKey.OP_READ, deadline);
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
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
            throw new IOException("worker exchange deadline elapsed");
    }

    private static void require(boolean valid) {
        if (!valid) throw new WorkerUnavailableException();
    }

    record Hello(UUID workerBootId, int leaseSeconds, int renewAfterSeconds) {}

    record Identity(UUID principalId, UUID accountId, UUID workspaceId, String serverSessionHash, UUID leaseId) {}
}
