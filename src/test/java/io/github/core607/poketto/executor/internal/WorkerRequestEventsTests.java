package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WorkerRequestEventsTests {
    @TempDir
    Path root;

    private final WorkerClient.Hello hello = new WorkerClient.Hello(UUID.randomUUID(), 15, 5);
    private final WorkerClient.Identity identity = new WorkerClient.Identity(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "private-session-marker", UUID.randomUUID());

    @Test
    void preparationRecordsItsCostsWithoutSendingOrExposingTheSignedPayload() throws Exception {
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        WorkerClient client = client(key.getPrivate());
        var data =
                new WorkerRequests.Exec(UUID.randomUUID().toString(), "a".repeat(40), "private-command-marker", 1000);
        Path file = root.resolve("prepared.jfr");
        WorkerClient.PreparedRequest prepared;
        try (var recording = new Recording()) {
            recording.enable(WorkerRequestEvents.Preparation.class);
            recording.start();
            prepared = client.prepare(hello, identity, "EXEC", data);
            recording.stop();
            recording.dump(file);
        }

        byte[] payload = Base64.getUrlDecoder().decode(prepared.envelope().payload());
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key.getPublic());
        verifier.update(payload);
        assertThat(verifier.verify(
                        Base64.getUrlDecoder().decode(prepared.envelope().signature())))
                .isTrue();
        JsonNode decoded = JsonMapper.shared().readTree(payload);
        assertThat(decoded.path("requestId").asString())
                .isEqualTo(prepared.requestId().toString());
        assertThat(decoded.path("data").path("command").asString()).isEqualTo(data.command());
        assertThat(events(file)).singleElement().satisfies(event -> {
            assertThat(event.getString("operation")).isEqualTo("EXEC");
            assertThat(event.getString("requestType")).isEqualTo("Exec");
            assertThat(event.getInt("payloadBytes")).isEqualTo(payload.length);
            assertThat(event.getLong("serializationNanos")).isPositive();
            assertThat(event.getLong("signingNanos")).isPositive();
            assertThat(event.getBoolean("prepared")).isTrue();
            assertThat(event.getStackTrace()).isNull();
            assertThat(event.toString())
                    .doesNotContain(
                            data.command(),
                            identity.serverSessionHash(),
                            identity.accountId().toString());
        });
    }

    @Test
    void aSigningFailureIsRecordedAsAnUnpreparedAttemptAndPreservesTheCause() throws Exception {
        var key = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        WorkerClient client = client(key.getPrivate());
        Path file = root.resolve("failed.jfr");
        try (var recording = new Recording()) {
            recording.enable(WorkerRequestEvents.Preparation.class);
            recording.start();
            assertThatThrownBy(() -> client.prepare(
                            hello,
                            identity,
                            "BRIDGE_POLL",
                            new WorkerRequests.BridgePoll(UUID.randomUUID().toString())))
                    .isInstanceOf(WorkerUnavailableException.class)
                    .hasCauseInstanceOf(InvalidKeyException.class);
            recording.stop();
            recording.dump(file);
        }

        assertThat(events(file)).singleElement().satisfies(event -> {
            assertThat(event.getString("operation")).isEqualTo("BRIDGE_POLL");
            assertThat(event.getString("requestType")).isEqualTo("BridgePoll");
            assertThat(event.getInt("payloadBytes")).isPositive();
            assertThat(event.getLong("serializationNanos")).isPositive();
            assertThat(event.getLong("signingNanos")).isPositive();
            assertThat(event.getBoolean("prepared")).isFalse();
        });
    }

    private WorkerClient client(PrivateKey key) {
        return new WorkerClient(
                root.resolve("unused.sock"),
                key,
                () -> {
                    throw new AssertionError("Preparation must not open the socket");
                },
                channel -> {
                    throw new AssertionError("Preparation must not connect to the peer");
                },
                JsonMapper.shared(),
                Clock.systemUTC());
    }

    private static List<RecordedEvent> events(Path file) throws Exception {
        return RecordingFile.readAllEvents(file).stream()
                .filter(event -> event.getEventType().getName().equals("poketto.WorkerRequestPreparation"))
                .filter(event -> event.getThread().getJavaThreadId()
                        == Thread.currentThread().threadId())
                .toList();
    }
}
