package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Wire peers test caller ownership and publication interpretation; native execution is checked separately. */
class SandcrabWorkspaceTests {
    private static final String ROOT = "7355a29f-fe96-4cd3-828e-d1e887bf3ef2";
    private static final String EPOCH = "13289a65-c1e2-451c-97c9-07cba1a0f58f";
    private static final String FILE = "3b45286e-07e5-4aa8-8ddc-99787b075f2e";
    private static final String OTHER = "79f4b64f-5a1a-4786-b823-ea899280a0ac";

    private static String session(String state) {
        return """
                {"id":"%s","serverId":"%s","profile":"python","state":"%s",
                 "expiresAtMillis":1900000000000,"activeRunId":null,"volumeId":null,
                 "code":null,"parentSessionId":null}
                """.formatted(ROOT, EPOCH, state);
    }

    private static SandcrabProtocol.Session ready() {
        return SandcrabProtocol.read(session("ready").getBytes(StandardCharsets.UTF_8), SandcrabProtocol.Session.class);
    }

    private static SandcrabProtocol.UploadPlan plan(byte[] data) {
        return new SandcrabProtocol.UploadPlan(
                FILE, EPOCH, ROOT, "input.bin", "raw", data.length, SandcrabProtocol.sha256(data), false, true, 0640);
    }

    @Test
    void renewalUsesTheExistingSessionAndKeepsItsEpochAndWorkspaceScope() throws Exception {
        try (var peer = new SandboxPeer(channel -> {
            SandboxPeer.Request request = SandboxPeer.request(channel);
            assertThat(request.head()).startsWith("POST /v1/sessions/" + ROOT + "/renew HTTP/1.1");
            assertThat(SandcrabProtocol.read(request.body(), SandcrabProtocol.Renewal.class)
                            .ttlMillis())
                    .isEqualTo(90_000);
            SandboxPeer.reply(channel, 200, session("ready"));
        })) {
            var client = new SandcrabClient(peer.client(channel -> {}));
            assertThat(client.renew(ready(), 90_000)).isEqualTo(ready());
            assertThat(peer.requests()).isEqualTo(1);
        }
        for (String reply :
                List.of(session("ready").replace(EPOCH, OTHER), session("ready").replace("python", "other"))) {
            try (var peer = new SandboxPeer(channel -> {
                SandboxPeer.request(channel);
                SandboxPeer.reply(channel, 200, reply);
            })) {
                var client = new SandcrabClient(peer.client(channel -> {}));
                assertThatThrownBy(() -> client.renew(ready(), 90_000)).isInstanceOf(WorkerUnavailableException.class);
                assertThat(peer.requests()).isEqualTo(1);
            }
        }
    }

    private static String upload(SandcrabProtocol.UploadPlan plan, String state, String publication) {
        return """
                {"id":"%s","serverId":"%s","sessionId":"%s","profile":"python","volumeId":null,
                 "path":"input.bin","format":"raw","bytes":%s,"sha256":"%s","planDigest":"%s",
                 "state":"%s","code":null,"publication":"%s"}
                """.formatted(FILE, EPOCH, ROOT, plan.bytes(), plan.sha256(), "a".repeat(64), state, publication);
    }

    @Test
    void binaryAndEmptyUploadsKeepTheirBytesAndDeclareTheExactLength() throws Exception {
        byte[] binary = new byte[512 * 1024];
        for (int index = 0; index < binary.length; index++) {
            binary[index] = (byte) index;
        }
        for (byte[] bytes : List.of(binary, new byte[0])) {
            SandcrabProtocol.UploadPlan plan = plan(bytes);
            try (var peer = new SandboxPeer(channel -> {
                SandboxPeer.Request request = SandboxPeer.request(channel);
                if (request.head().startsWith("POST /v1/uploads HTTP/1.1")) {
                    assertThat(SandcrabProtocol.read(request.body(), SandcrabProtocol.UploadPlan.class))
                            .isEqualTo(plan);
                    SandboxPeer.reply(channel, 201, upload(plan, "waiting", "none"));
                } else {
                    assertThat(request.head())
                            .startsWith("PUT /v1/uploads/" + FILE + "/content HTTP/1.1")
                            .contains(
                                    "Content-Length: " + bytes.length + "\r\n",
                                    "Content-Type: application/octet-stream\r\n");
                    assertThat(request.body()).isEqualTo(bytes);
                    SandboxPeer.reply(channel, 200, upload(plan, "succeeded", "committed"));
                }
            })) {
                var client = new SandcrabClient(peer.client(channel -> {}));
                assertThat(client.register(ready(), plan).state()).isEqualTo("waiting");
                assertThat(client.send(plan, bytes).succeeded()).isTrue();
                assertThat(peer.requests()).isEqualTo(2);
            }
        }
    }

    @Test
    void aLostUploadReplyCanBeInspectedWithoutSendingItsBytesAgain() throws Exception {
        byte[] bytes = "registered content".getBytes(StandardCharsets.UTF_8);
        SandcrabProtocol.UploadPlan plan = plan(bytes);
        var writes = new AtomicInteger();
        try (var peer = new SandboxPeer(channel -> {
            SandboxPeer.Request request = SandboxPeer.request(channel);
            if (request.head().startsWith("PUT ")) {
                writes.incrementAndGet();
                assertThat(request.body()).isEqualTo(bytes);
                return;
            }
            assertThat(request.head()).startsWith("GET /v1/uploads/" + FILE + " HTTP/1.1");
            SandboxPeer.reply(channel, 200, upload(plan, "succeeded", "committed"));
        })) {
            var client = new SandcrabClient(peer.client(channel -> {}));
            assertThatThrownBy(() -> client.send(plan, bytes)).isInstanceOf(WorkerUnavailableException.class);
            assertThat(client.upload(plan.reference()).succeeded()).isTrue();
            assertThat(writes.get()).isEqualTo(1);
            assertThat(peer.requests()).isEqualTo(2);
        }
    }

    @Test
    void uncertainPublicationAndUncontainedSessionsDoNotBecomeSuccessOrCleanup() throws Exception {
        byte[] bytes = new byte[0];
        SandcrabProtocol.UploadPlan plan = plan(bytes);
        try (var peer = new SandboxPeer(channel -> {
            SandboxPeer.request(channel);
            SandboxPeer.reply(channel, 200, upload(plan, "unknown", "unknown"));
        })) {
            var client = new SandcrabClient(peer.client(channel -> {}));
            assertThat(client.send(plan, bytes).succeeded()).isFalse();
            SandcrabProtocol.Session closing = SandcrabProtocol.read(
                    session("closing").getBytes(StandardCharsets.UTF_8), SandcrabProtocol.Session.class);
            assertThat(closing.terminal()).isFalse();
            assertThatThrownBy(() -> client.forget(closing)).isInstanceOf(WorkerUnavailableException.class);
            assertThat(peer.requests()).isEqualTo(1);
        }
        assertThatThrownBy(() -> SandcrabProtocol.read(
                        upload(plan, "succeeded", "unknown").getBytes(StandardCharsets.UTF_8),
                        SandcrabProtocol.Upload.class))
                .isInstanceOf(WorkerUnavailableException.class);
    }

    @Test
    void aReceiptForDifferentBytesDoesNotAcknowledgeTheSentManifest() throws Exception {
        byte[] bytes = "original".getBytes(StandardCharsets.UTF_8);
        SandcrabProtocol.UploadPlan plan = plan(bytes);
        try (var peer = new SandboxPeer(channel -> {
            SandboxPeer.request(channel);
            SandboxPeer.reply(
                    channel, 200, upload(plan, "succeeded", "committed").replace(plan.sha256(), "b".repeat(64)));
        })) {
            var client = new SandcrabClient(peer.client(channel -> {}));
            assertThatThrownBy(() -> client.send(plan, bytes)).isInstanceOf(WorkerUnavailableException.class);
            assertThat(peer.requests()).isEqualTo(1);
        }
    }

    @Test
    void sessionAdmissionCannotChangeItsVolumeOrOmitNullableOwnershipFields() throws Exception {
        var plan = new SandcrabProtocol.SessionPlan(ROOT, EPOCH, "python", 10_000, null);
        for (String reply : List.of(
                session("ready").replace("\"volumeId\":null", "\"volumeId\":\"" + OTHER + "\""),
                session("ready").replace("\"volumeId\":null,", ""),
                session("ready").replace("\"parentSessionId\":null", "\"parentSessionId\":\"" + OTHER + "\""))) {
            try (var peer = new SandboxPeer(channel -> {
                SandboxPeer.request(channel);
                SandboxPeer.reply(channel, 202, reply);
            })) {
                var client = new SandcrabClient(peer.client(channel -> {}));
                assertThatThrownBy(() -> client.open(plan)).isInstanceOf(WorkerUnavailableException.class);
                assertThat(peer.requests()).isEqualTo(1);
            }
        }
    }
}
