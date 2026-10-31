package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Real Unix sockets exercise framing and dispatch; the peer is not a sandbox. */
class SandboxHttpTests {
    @Test
    void prefetchedEventBodyArmsTheIdleDeadlineBeforeAnotherSocketRead() throws Exception {
        eventIdleDeadline(true);
    }

    @Test
    void eventBodyReadAfterTheHeaderAlsoUsesTheIdleDeadline() throws Exception {
        eventIdleDeadline(false);
    }

    @Test
    void eventProgressCannotExtendTheTotalDeadline() throws Exception {
        var finished = new CountDownLatch(1);
        var received = new AtomicInteger();
        try (var peer = new Peer(channel -> {
            head(Channels.newInputStream(channel));
            Channels.newOutputStream(channel)
                    .write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            for (int index = 0; index < 100 && !await(finished, 20); index++) {
                Channels.newOutputStream(channel).write("1\r\nx\r\n".getBytes(StandardCharsets.US_ASCII));
            }
        })) {
            long started = System.nanoTime();
            assertThatThrownBy(() -> peer.client(channel -> {})
                            .events(
                                    "/v1/runs/fixture/events",
                                    null,
                                    Duration.ofMillis(200),
                                    Duration.ofSeconds(1),
                                    (response, body) -> {
                                        try {
                                            while (body.read() >= 0) {
                                                received.incrementAndGet();
                                            }
                                            return null;
                                        } finally {
                                            finished.countDown();
                                        }
                                    }))
                    .isInstanceOf(WorkerUnavailableException.class)
                    .hasRootCauseMessage("worker exchange deadline elapsed");
            assertThat(received).hasValueGreaterThan(1);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        }
    }

    @Test
    void queuedEventBytesAfterALocalHandlerPauseDoNotCountAsPeerSilence() throws Exception {
        var bodyStarted = new CountDownLatch(1);
        var nextRequested = new CountDownLatch(1);
        var nextQueued = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        try (var peer = new Peer(channel -> {
            head(Channels.newInputStream(channel));
            Channels.newOutputStream(channel)
                    .write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            await(bodyStarted);
            Channels.newOutputStream(channel).write("1\r\nx\r\n".getBytes(StandardCharsets.US_ASCII));
            await(nextRequested);
            Channels.newOutputStream(channel).write("1\r\ny\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            nextQueued.countDown();
            await(finished);
        })) {
            try {
                int next = peer.client(channel -> {})
                        .events(
                                "/v1/runs/fixture/events",
                                null,
                                Duration.ofSeconds(3),
                                Duration.ofMillis(100),
                                (response, body) -> {
                                    bodyStarted.countDown();
                                    assertThat(body.read()).isEqualTo('x');
                                    nextRequested.countDown();
                                    await(nextQueued);
                                    // The host handler owns this time; the peer has already supplied its next bytes.
                                    await(new CountDownLatch(1), 200);
                                    int value = body.read();
                                    assertThat(body.read()).isEqualTo(-1);
                                    return value;
                                });
                assertThat(next).isEqualTo('y');
            } finally {
                finished.countDown();
            }
        }
    }

    @Test
    void nativeStatusesAndEmptyRepliesAreReturnedWithoutRetryOrRedirect() throws Exception {
        for (int status : new int[] {202, 204, 307, 409, 503}) {
            byte[] body = status == 204 ? new byte[0] : "{}".getBytes(StandardCharsets.UTF_8);
            try (var peer = new Peer(channel -> {
                InputStream input = Channels.newInputStream(channel);
                assertThat(head(input)).startsWith("POST /v1/runs HTTP/1.1\r\n");
                assertThat(input.readNBytes(2)).containsExactly((byte) '{', (byte) '}');
                String header = "HTTP/1.1 " + status + " Outcome\r\nContent-Length: " + body.length
                        + "\r\nLocation: /must-not-follow\r\n"
                        + (body.length == 0 ? "" : "Content-Type: application/json\r\n") + "\r\n";
                Channels.newOutputStream(channel).write(header.getBytes(StandardCharsets.US_ASCII));
                Channels.newOutputStream(channel).write(body);
            })) {
                Reply reply = exchange(peer.client(channel -> {}), Duration.ofSeconds(2));
                assertThat(reply.status()).isEqualTo(status);
                assertThat(reply.body()).isEqualTo(body);
            }
        }
    }

    @Test
    void ambiguousOrUnboundedResponseHeadsAreRefused() throws Exception {
        for (String header : new String[] {
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 2\r\nContent-Type: application/json\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\nContent-Type: application/json\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: -1\r\nContent-Type: application/json\r\n\r\n",
            "HTTP/1.1 100 Continue\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nPadding: " + "x".repeat(8193)
                    + "\r\n\r\n"
        }) {
            try (var peer = new Peer(channel -> {
                InputStream input = Channels.newInputStream(channel);
                head(input);
                assertThat(input.readNBytes(2)).hasSize(2);
                // Invalid headers permit immediate disconnect; submit the fixture in one write.
                String response = header + (header.startsWith("HTTP/1.1 100 ") ? "" : "{}");
                Channels.newOutputStream(channel).write(response.getBytes(StandardCharsets.US_ASCII));
            })) {
                assertThatThrownBy(() -> exchange(peer.client(channel -> {}), Duration.ofSeconds(2)))
                        .isInstanceOf(WorkerUnavailableException.class);
            }
        }
    }

    @Test
    void truncatedReplyKeepsTheOutcomeUncertainWithoutAnotherDispatch() throws Exception {
        try (var peer = new Peer(channel -> {
            InputStream input = Channels.newInputStream(channel);
            head(input);
            assertThat(input.readNBytes(2)).hasSize(2);
            Channels.newOutputStream(channel)
                    .write("HTTP/1.1 202 Accepted\r\nContent-Type: application/json\r\nContent-Length: 5\r\n\r\n{}"
                            .getBytes(StandardCharsets.US_ASCII));
        })) {
            assertThatThrownBy(() -> exchange(peer.client(channel -> {}), Duration.ofSeconds(2)))
                    .isInstanceOfSatisfying(
                            WorkerUnavailableException.class,
                            failure -> assertThat(failure.mayHaveExecuted()).isTrue())
                    .hasCauseInstanceOf(IOException.class);
        }
    }

    @Test
    void peerAuthenticationPrecedesRequestBytes() throws Exception {
        try (var peer = new Peer(
                channel -> assertThat(Channels.newInputStream(channel).read()).isEqualTo(-1))) {
            SandboxHttp client = peer.client(channel -> {
                throw new WorkerUnavailableException();
            });
            assertThatThrownBy(() -> exchange(client, Duration.ofSeconds(2)))
                    .isInstanceOfSatisfying(
                            WorkerUnavailableException.class,
                            failure -> assertThat(failure.mayHaveExecuted()).isFalse());
        }
    }

    private static Reply exchange(SandboxHttp client, Duration timeout) {
        return client.exchange(
                "POST",
                "/v1/runs",
                "application/json",
                "{}".getBytes(StandardCharsets.UTF_8),
                timeout,
                Duration.ZERO,
                (header, body) -> {
                    assertThat(header.length()).isBetween(0L, 1024L);
                    return new Reply(header.status(), body.readNBytes((int) header.length()));
                });
    }

    private static void eventIdleDeadline(boolean prefetched) throws Exception {
        String header = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n";
        String chunk = "1\r\nx\r\n";
        var queued = new CountDownLatch(1);
        var bodyStarted = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        try (var peer = new Peer(channel -> {
            Channels.newOutputStream(channel)
                    .write((header + (prefetched ? chunk : "")).getBytes(StandardCharsets.US_ASCII));
            queued.countDown();
            assertThat(head(Channels.newInputStream(channel))).startsWith("GET /v1/runs/fixture/events HTTP/1.1");
            if (!prefetched) {
                await(bodyStarted);
                Channels.newOutputStream(channel).write(chunk.getBytes(StandardCharsets.US_ASCII));
            }
            await(finished);
        })) {
            SandboxHttp client = peer.client(channel -> {
                try {
                    await(queued);
                } catch (IOException failure) {
                    throw new WorkerUnavailableException(failure);
                }
            });
            long started = System.nanoTime();
            try {
                assertThatThrownBy(() -> client.events(
                                "/v1/runs/fixture/events",
                                null,
                                Duration.ofSeconds(3),
                                Duration.ofMillis(100),
                                (response, body) -> {
                                    bodyStarted.countDown();
                                    assertThat(body.read()).isEqualTo('x');
                                    return body.read();
                                }))
                        .isInstanceOf(WorkerUnavailableException.class)
                        .hasRootCauseMessage("worker exchange deadline elapsed");
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            } finally {
                finished.countDown();
            }
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        if (!await(latch, 5000)) {
            throw new IOException("Event deadline fixture did not advance");
        }
    }

    private static boolean await(CountDownLatch latch, long millis) throws IOException {
        try {
            return latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Event deadline fixture was interrupted", failure);
        }
    }

    private record Reply(int status, byte[] body) {}

    private static String head(InputStream input) throws IOException {
        var result = new StringBuilder();
        while (!result.toString().endsWith("\r\n\r\n")) {
            int value = input.read();
            if (value < 0 || result.length() > 8192) {
                throw new IOException("Incomplete test request header");
            }
            result.append((char) value);
        }
        return result.toString();
    }

    @FunctionalInterface
    private interface Exchange {
        void respond(SocketChannel channel) throws IOException;
    }

    private static final class Peer implements AutoCloseable {
        private final ServerSocketChannel server;
        private final UnixDomainSocketAddress address;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final Future<?> task;

        Peer(Exchange exchange) throws IOException {
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(null);
            address = (UnixDomainSocketAddress) server.getLocalAddress();
            task = executor.submit(() -> {
                try (SocketChannel channel = server.accept()) {
                    exchange.respond(channel);
                }
                server.close();
                return null;
            });
        }

        SandboxHttp client(Consumer<SocketChannel> verification) {
            return new SandboxHttp(address.getPath(), () -> {}, verification);
        }

        @Override
        public void close() throws Exception {
            try {
                server.close();
                task.get(5, TimeUnit.SECONDS);
            } finally {
                executor.close();
                Files.deleteIfExists(address.getPath());
            }
        }
    }
}
