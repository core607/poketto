package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Real local HTTP peers pin observation framing and one-shot replies without executing a sandbox. */
class SandcrabEventsTests {
    private static final String RUN = "9ca35360-0845-4fe3-88ef-d3e68ec8a222";
    private static final String EPOCH = "775437cd-82d5-4f00-a99d-a5922b287d2e";
    private static final String INCARNATION = "fdac1d98-cea2-429a-8c70-212155137833";
    private static final String CALL = "b165656d-4bf4-4362-8036-552ca02a8282";
    private static final String REQUEST = "2215124b-e0ed-48e2-af73-6716994b5e25";
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final SandcrabProtocol.Reference REFERENCE = new SandcrabProtocol.Reference(RUN, EPOCH);
    private static final String RUNNING = """
            {"id":"%s","serverId":"%s","profile":"python","state":"running","result":null}
            """.formatted(RUN, EPOCH).strip();
    private static final String SUCCEEDED =
            RUNNING.replace("\"running\"", "\"succeeded\"").replace("null", "{\"exitCode\":0,\"truncated\":false}");
    private static final String EMPTY_CALLS = "{\"closed\":false,\"error\":null,\"calls\":[]}";

    @Test
    void explicitOutputCursorPreservesBinaryOffsetsAcrossChunksAndTerminalNeedsNoZeroChunk() throws Exception {
        var observed = new ArrayList<SandcrabEvents.Output>();
        var after = new SandcrabEventProtocol.OutputCursor(INCARNATION, 2, 1);
        String frames = frame(after.text(), "state", RUNNING)
                + ": keep-alive\r\n\r\n"
                + frame(INCARNATION + ":4:1", "stdout", "{\"offset\":2,\"base64\":\"/wA=\"}")
                + frame(INCARNATION + ":4:2", "stderr", "{\"offset\":1,\"base64\":\"4g==\"}")
                + frame(INCARNATION + ":4:2", "result", outputResult(4, 2));
        try (var peer = new SandboxPeer(channel -> {
            SandboxPeer.Request request = SandboxPeer.request(channel);
            assertThat(request.head()).contains("Last-Event-ID: " + after.text() + "\r\n");
            events(channel, frames, 7, false);
        })) {
            SandcrabEventProtocol.Run result = client(peer).observe(REFERENCE, after, TIMEOUT, observed::add);
            assertThat(result.terminal()).isTrue();
            assertThat(result.result().success()).isTrue();
            assertThat(observed).hasSize(2);
            assertThat(observed.getFirst().bytes()).containsExactly((byte) 0xff, (byte) 0);
            assertThat(observed.getLast().bytes()).containsExactly((byte) 0xe2);
            assertThat(observed.getLast().cursor().text()).isEqualTo(INCARNATION + ":4:2");
            assertThat(peer.requests()).isEqualTo(1);
        }
    }

    @Test
    void opaqueMaximumCallUsesOneObserverAndRepliesOnlyToItsDaemonHandle() throws Exception {
        byte[] payload = new byte[65_536];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) index;
        }
        byte[] reply = new byte[] {(byte) 0xff, 0, 3};
        String request = call(payload, 1);
        String receipt = metadata(payload, 1, "replied", SandcrabProtocol.sha256(reply));
        var handled = new AtomicInteger();
        var posted = new AtomicReference<SandboxPeer.Request>();
        try (var peer = new SandboxPeer(channel -> {
            SandboxPeer.Request incoming = SandboxPeer.request(channel);
            if (incoming.head().startsWith("POST ")) {
                posted.set(incoming);
                SandboxPeer.reply(channel, 200, receipt);
            } else if (incoming.head().contains("/calls/events ")) {
                assertThat(incoming.head()).startsWith("GET /v1/runs/" + RUN + "/calls/events ");
                assertThat(incoming.head()).doesNotContain("Last-Event-ID:");
                events(
                        channel,
                        callsState(0) + frame(INCARNATION + ":1", "call", request) + callResult(1, receipt),
                        32_767,
                        true);
            } else {
                throw new IOException("Host-call observation must not open an extra output handshake");
            }
        })) {
            SandcrabEventProtocol.Run result = client(peer).observeCalls(REFERENCE, TIMEOUT, observed -> {
                handled.incrementAndGet();
                assertThat(observed.run()).isEqualTo(REFERENCE);
                assertThat(observed.id()).isEqualTo(CALL);
                assertThat(observed.requestId()).isEqualTo(REQUEST);
                assertThat(observed.bytes()).isEqualTo(payload);
                return reply;
            });
            assertThat(result.terminal()).isTrue();
            assertThat(handled).hasValue(1);
            assertThat(posted.get().head()).startsWith("POST /v1/runs/" + RUN + "/calls/" + CALL + "/reply ");
            assertThat(new String(posted.get().body(), StandardCharsets.UTF_8)).isEqualTo("{\"base64\":\"/wAD\"}");
            assertThat(peer.requests()).isEqualTo(2);
        }
    }

    @Test
    void explicitCallCursorIsCheckedBeforeDeliveryAndDoesNotReplayEarlierHandlers() throws Exception {
        var after = new SandcrabEventProtocol.CallCursor(INCARNATION, 1);
        byte[] payload = new byte[0];
        String old = metadata(payload, 1, "replied", SandcrabProtocol.sha256(payload));
        String next =
                metadata(payload, 2, "abandoned", null).replace(CALL, EPOCH).replace(REQUEST, INCARNATION);
        var handled = new AtomicInteger();
        try (var peer = new SandboxPeer(channel -> {
            SandboxPeer.Request incoming = SandboxPeer.request(channel);
            assertThat(incoming.head()).startsWith("GET /v1/runs/" + RUN + "/calls/events ");
            assertThat(incoming.head()).contains("Last-Event-ID: " + after.text() + "\r\n");
            String request = call(payload, 2).replace(CALL, EPOCH).replace(REQUEST, INCARNATION);
            events(
                    channel,
                    callsState(1) + frame(INCARNATION + ":2", "call", request) + callResult(2, old + "," + next),
                    1024,
                    true);
        })) {
            assertThat(client(peer)
                            .observeCalls(REFERENCE, after, TIMEOUT, ignored -> handled.incrementAndGet())
                            .terminal())
                    .isTrue();
            assertThat(handled).hasValue(1);
            assertThat(peer.requests()).isEqualTo(1);
        }
    }

    @Test
    void epochOrIncarnationMismatchCannotInvokeAnOutsideHandler() throws Exception {
        for (String frames : List.of(
                callsState(0).replace(EPOCH, CALL),
                callsState(0).replace(RUN, CALL),
                callsState(0).replace("\"serverId\":\"" + EPOCH + "\",", ""))) {
            var handled = new AtomicInteger();
            try (var peer = new SandboxPeer(channel -> {
                SandboxPeer.request(channel);
                events(channel, frames, 1024, true);
            })) {
                assertThatThrownBy(() -> client(peer).observeCalls(REFERENCE, TIMEOUT, ignored -> {
                            handled.incrementAndGet();
                            return new byte[0];
                        }))
                        .isInstanceOf(WorkerUnavailableException.class);
                assertThat(handled).hasValue(0);
                assertThat(peer.requests()).isEqualTo(1);
            }
        }
        assertCallRejected(callsState(1), 0);
        try (var peer = new SandboxPeer(channel -> {
            SandboxPeer.request(channel);
            events(channel, callsState(0).replace(INCARNATION, EPOCH), 1024, true);
        })) {
            var after = new SandcrabEventProtocol.CallCursor(INCARNATION, 0);
            assertThatThrownBy(() -> client(peer).observeCalls(REFERENCE, after, TIMEOUT, ignored -> {
                        throw new AssertionError("Mismatched incarnation reached a host handler");
                    }))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThat(peer.requests()).isEqualTo(1);
        }
    }

    @Test
    void duplicateCallIdentityAndRegressingCursorsNeverRepeatTheHandler() throws Exception {
        String first = frame(INCARNATION + ":1", "call", call(new byte[0], 1));
        assertCallRejected(callsState(0) + first + frame(INCARNATION + ":2", "call", call(new byte[0], 2)), 1);
        assertCallRejected(callsState(0) + first + frame(INCARNATION + ":0", "call", call(new byte[0], 1)), 1);
        assertCallRejected(callsState(0) + first + frame(EPOCH + ":2", "call", call(new byte[0], 2)), 1);
    }

    @Test
    void malformedOpaqueBytesAndUnconfirmedResultsCannotReachTheHandlerOrAcknowledgeCompletion() throws Exception {
        String request = call(new byte[] {1}, 1);
        assertCallRejected(callsState(0) + frame(INCARNATION + ":1", "call", request.replace("AQ==", "AQ")), 0);
        assertCallRejected(callsState(0) + frame(INCARNATION + ":1", "call", request.replace("AQ==", "Ag==")), 0);
        assertCallRejected(callsState(0) + ": keep-alive\n\n", 0);
        assertCallRejected(
                callsState(0) + frame(INCARNATION + ":0", "error", "{\"code\":\"CONTAINMENT_UNCONFIRMED\"}"), 0);
        assertCallRejected(callsState(0) + callResult(0, "").replace(EPOCH, CALL), 0);
        assertCallRejected(callsState(0) + callResult(0, "").replace("\"closed\":true", "\"closed\":false"), 0);
    }

    @Test
    void aLostReplyDoesNotRepeatPublicationOrTheBusinessHandler() throws Exception {
        var handled = new AtomicInteger();
        var posts = new AtomicInteger();
        try (var peer = new SandboxPeer(channel -> {
            SandboxPeer.Request incoming = SandboxPeer.request(channel);
            if (incoming.head().startsWith("POST ")) {
                posts.incrementAndGet();
            } else if (incoming.head().contains("/calls/events ")) {
                events(channel, callsState(0) + frame(INCARNATION + ":1", "call", call(new byte[0], 1)), 1024, true);
            } else {
                throw new IOException("Host-call observation must use one stream");
            }
        })) {
            assertThatThrownBy(() -> client(peer).observeCalls(REFERENCE, TIMEOUT, ignored -> {
                        handled.incrementAndGet();
                        return new byte[0];
                    }))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThat(handled).hasValue(1);
            assertThat(posts).hasValue(1);
            assertThat(peer.requests()).isEqualTo(2);
        }
    }

    @Test
    void outputFramingAndOffsetsRejectAmbiguityBeforeAcknowledgingAResult() throws Exception {
        for (String frames : List.of(
                outputState() + frame(INCARNATION + ":1:0", "stdout", "{\"offset\":1,\"base64\":\"AQ==\"}"),
                outputState() + frame(INCARNATION + ":0:0", "result", outputResult(1, 0)),
                outputState()
                        + frame(
                                INCARNATION + ":0:0",
                                "result",
                                outputResult(0, 0).replace("succeeded", "closing")),
                outputState() + "id: " + INCARNATION + ":0:0\nevent: result\ndata: {}\ndata: {}\n\n",
                outputState() + ":" + "x".repeat(16_384) + "\n\n",
                outputState() + "id: " + INCARNATION + ":0:0\nevent: result\ndata: {}\n")) {
            try (var peer = new SandboxPeer(channel -> {
                SandboxPeer.request(channel);
                events(channel, frames, 65_536, true);
            })) {
                assertThatThrownBy(() -> client(peer).observe(REFERENCE, null, TIMEOUT, ignored -> {}))
                        .isInstanceOf(WorkerUnavailableException.class);
                assertThat(peer.requests()).isEqualTo(1);
            }
        }
    }

    @Test
    void observationRefusalRemainsDistinctFromConfirmedCompletion() throws Exception {
        try (var peer = new SandboxPeer(channel -> {
            SandboxPeer.request(channel);
            SandboxPeer.reply(channel, 409, "{\"error\":{\"code\":\"EVENT_CURSOR_MISMATCH\"}}");
        })) {
            assertThatThrownBy(() -> client(peer).observe(REFERENCE, null, TIMEOUT, ignored -> {}))
                    .isInstanceOfSatisfying(
                            SandcrabClient.Rejected.class,
                            error -> assertThat(error.code()).isEqualTo("EVENT_CURSOR_MISMATCH"));
            assertThat(peer.requests()).isEqualTo(1);
        }
    }

    @Test
    void retainedCallInventoryBindsTheRunEpochAndIncarnation() throws Exception {
        String inventory = "{\"runId\":\"" + RUN + "\",\"serverId\":\"" + EPOCH + "\",\"incarnation\":\"" + INCARNATION
                + "\",\"closed\":true,\"error\":null,\"calls\":[]}";
        for (boolean sameEpoch : new boolean[] {true, false}) {
            try (var peer = new SandboxPeer(channel -> {
                SandboxPeer.Request request = SandboxPeer.request(channel);
                assertThat(request.head()).startsWith("GET /v1/runs/" + RUN + "/calls ");
                SandboxPeer.reply(channel, 200, sameEpoch ? inventory : inventory.replace(EPOCH, CALL));
            })) {
                if (sameEpoch) {
                    SandcrabEventProtocol.CallInventory value = client(peer).inventory(REFERENCE);
                    assertThat(value.incarnation()).isEqualTo(INCARNATION);
                    assertThat(value.closed()).isTrue();
                } else {
                    assertThatThrownBy(() -> client(peer).inventory(REFERENCE))
                            .isInstanceOf(WorkerUnavailableException.class);
                }
                assertThat(peer.requests()).isEqualTo(1);
            }
        }
    }

    private static void assertCallRejected(String frames, int delivered) throws Exception {
        var callbacks = new AtomicInteger();
        try (var peer = new SandboxPeer(channel -> {
            SandboxPeer.Request request = SandboxPeer.request(channel);
            assertThat(request.head()).startsWith("GET /v1/runs/" + RUN + "/calls/events ");
            events(channel, frames, 65_536, true);
        })) {
            assertThatThrownBy(() ->
                            client(peer).observeCalls(REFERENCE, null, TIMEOUT, ignored -> callbacks.incrementAndGet()))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThat(callbacks).hasValue(delivered);
            assertThat(peer.requests()).isEqualTo(1);
        }
    }

    private static SandcrabEvents client(SandboxPeer peer) {
        return new SandcrabEvents(peer.client(channel -> {}));
    }

    private static String frame(String cursor, String kind, String json) {
        return "id: " + cursor + "\nevent: " + kind + "\ndata: " + json + "\n\n";
    }

    private static String outputState() {
        return frame(INCARNATION + ":0:0", "state", RUNNING);
    }

    private static String outputResult(int stdout, int stderr) {
        return SUCCEEDED.substring(0, SUCCEEDED.length() - 1) + ",\"stdoutBytes\":" + stdout + ",\"stderrBytes\":"
                + stderr + "}";
    }

    private static String callsState(int sequence) {
        String calls = sequence == 0
                ? EMPTY_CALLS
                : "{\"closed\":false,\"error\":null,\"calls\":["
                        + metadata(new byte[0], 1, "replied", SandcrabProtocol.sha256(new byte[0])) + "]}";
        return frame(
                INCARNATION + ":" + sequence,
                "state",
                "{\"runId\":\"" + RUN + "\",\"serverId\":\"" + EPOCH + "\",\"calls\":" + calls + "}");
    }

    private static String callResult(int sequence, String metadata) {
        return frame(
                INCARNATION + ":" + sequence,
                "result",
                "{\"run\":" + SUCCEEDED + ",\"calls\":{\"closed\":true,\"error\":null,\"calls\":[" + metadata + "]}}");
    }

    private static String metadata(byte[] payload, int sequence, String state, String replyDigest) {
        return """
                {"id":"%s","requestId":"%s","sequence":%d,"state":"%s","requestSha256":"%s","replySha256":%s}
                """.formatted(
                CALL,
                REQUEST,
                sequence,
                state,
                SandcrabProtocol.sha256(payload),
                replyDigest == null ? "null" : "\"" + replyDigest + "\"").strip();
    }

    private static String call(byte[] bytes, int sequence) {
        String info = metadata(bytes, sequence, "pending", null);
        return info.substring(0, info.length() - 1) + ",\"base64\":\""
                + Base64.getEncoder().encodeToString(bytes) + "\"}";
    }

    private static void events(SocketChannel channel, String frames, int chunkBytes, boolean end) throws IOException {
        var wire = new ByteArrayOutputStream();
        wire.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII));
        byte[] bytes = frames.getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset < bytes.length; offset += chunkBytes) {
            int count = Math.min(chunkBytes, bytes.length - offset);
            wire.write((Integer.toHexString(count) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            wire.write(bytes, offset, count);
            wire.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        if (end) {
            wire.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        Channels.newOutputStream(channel).write(wire.toByteArray());
    }
}
