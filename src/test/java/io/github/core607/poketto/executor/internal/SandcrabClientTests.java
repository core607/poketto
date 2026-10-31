package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SandcrabClientTests {
    private static final String ID = "9ca35360-0845-4fe3-88ef-d3e68ec8a222";
    private static final String EPOCH = "775437cd-82d5-4f00-a99d-a5922b287d2e";
    private static final String OTHER = "fdac1d98-cea2-429a-8c70-212155137833";
    private static final String RUNNING = """
            {"id":"%s","serverId":"%s","profile":"python","state":"running","result":null}
            """.formatted(ID, EPOCH);
    private static final String SUCCEEDED = RUNNING.replace("\"running\"", "\"succeeded\"")
            .replace(
                    "\"result\":null",
                    "\"result\":{\"exitCode\":0,\"stdout\":\"42\\n\",\"stderr\":\"\",\"truncated\":false}");

    private static SandcrabProtocol.RunRequest plan() {
        return new SandcrabProtocol.RunRequest(ID, EPOCH, "python", List.of("python3", "-c", "print(42)"), 1000, 4096);
    }

    @Test
    void admissionUsesOnlyNativeFieldsAndPreservesTheRecoveryIdentity() throws Exception {
        var request = new AtomicReference<SandboxPeer.Request>();
        try (var peer = new SandboxPeer(channel -> {
            request.set(SandboxPeer.request(channel));
            SandboxPeer.reply(channel, 202, RUNNING);
        })) {
            var client = new SandcrabClient(peer.client(channel -> {}));
            SandcrabProtocol.Run run = client.start(plan());
            assertThat(run.reference()).isEqualTo(plan().reference());
            assertThat(run.terminal()).isFalse();
            assertThat(peer.requests()).isEqualTo(1);
            assertThat(request.get().head()).startsWith("POST /v1/runs HTTP/1.1\r\n");
            assertThat(new String(request.get().body(), StandardCharsets.UTF_8))
                    .contains("\"timeoutMillis\":1000", "\"outputLimitBytes\":4096")
                    .doesNotContain("signature", "principalId", "accountId", "workspaceId", "operation");
        }
    }

    @Test
    void lostAdmissionResponseDoesNotDispatchTheMutationAgain() throws Exception {
        try (var peer = new SandboxPeer(SandboxPeer::request)) {
            var client = new SandcrabClient(peer.client(channel -> {}));
            assertThatThrownBy(() -> client.start(plan())).isInstanceOf(WorkerUnavailableException.class);
            assertThat(peer.requests()).isEqualTo(1);
        }
    }

    @Test
    void mismatchedIdsAndEpochsCannotAcknowledgeTheRequestedRun() throws Exception {
        for (String body : List.of(RUNNING.replace(ID, OTHER), RUNNING.replace(EPOCH, OTHER))) {
            try (var peer = new SandboxPeer(channel -> {
                SandboxPeer.request(channel);
                SandboxPeer.reply(channel, 202, body);
            })) {
                var client = new SandcrabClient(peer.client(channel -> {}));
                assertThatThrownBy(() -> client.start(plan())).isInstanceOf(WorkerUnavailableException.class);
                assertThat(peer.requests()).isEqualTo(1);
            }
        }
    }

    @Test
    void refusalsAndServerFailureStayDistinctWithoutRetryingEither() throws Exception {
        for (int status : new int[] {409, 503}) {
            try (var peer = new SandboxPeer(channel -> {
                SandboxPeer.request(channel);
                SandboxPeer.reply(channel, status, "{\"error\":{\"code\":\"STOPPING\"}}");
            })) {
                var client = new SandcrabClient(peer.client(channel -> {}));
                if (status == 409) {
                    assertThatThrownBy(() -> client.start(plan()))
                            .isInstanceOfSatisfying(SandcrabClient.Rejected.class, error -> {
                                assertThat(error.status()).isEqualTo(409);
                                assertThat(error.code()).isEqualTo("STOPPING");
                            });
                } else {
                    assertThatThrownBy(() -> client.start(plan()))
                            .isInstanceOf(WorkerUnavailableException.class)
                            .hasRootCauseMessage("Native sandbox returned 503 STOPPING");
                }
                assertThat(peer.requests()).isEqualTo(1);
            }
        }
    }

    @Test
    void malformedSuccessCannotDefaultMissingOrContradictoryFieldsIntoSuccess() {
        assertThat(SandcrabProtocol.read(SUCCEEDED.getBytes(StandardCharsets.UTF_8), SandcrabProtocol.Run.class)
                        .result()
                        .success())
                .isTrue();
        for (String body : List.of(
                SUCCEEDED.replace("\"exitCode\":0,", ""),
                SUCCEEDED.replace(",\"truncated\":false", ""),
                SUCCEEDED.replace("\"exitCode\":0", "\"exitCode\":0.0"),
                SUCCEEDED.replace("\"exitCode\":0", "\"exitCode\":\"fixture-private-source\""),
                SUCCEEDED.replace("\"truncated\":false", "\"truncated\":\"false\""),
                SUCCEEDED.replace("\"exitCode\":0", "\"exitCode\":0,\"exitCode\":1"),
                SUCCEEDED.replace("\"exitCode\":0", "\"exitCode\":0,\"code\":\"RUN_FAILED\""),
                SUCCEEDED + "{}")) {
            assertThatThrownBy(() ->
                            SandcrabProtocol.read(body.getBytes(StandardCharsets.UTF_8), SandcrabProtocol.Run.class))
                    .isInstanceOf(WorkerUnavailableException.class)
                    .hasNoCause()
                    .hasMessageNotContaining("fixture-private-source");
        }
    }

    @Test
    void closingDoesNotAuthorizeForgettingAndCancellationNeedsItsOwnTerminalObservation() throws Exception {
        String closing = RUNNING.replace("\"running\"", "\"closing\"")
                .replace("\"result\":null", "\"result\":{\"code\":\"CONTAINMENT_UNCONFIRMED\"}");
        var request = new AtomicReference<SandboxPeer.Request>();
        try (var peer = new SandboxPeer(channel -> {
            request.set(SandboxPeer.request(channel));
            SandboxPeer.reply(channel, 200, closing);
        })) {
            var client = new SandcrabClient(peer.client(channel -> {}));
            SandcrabProtocol.Run run = client.cancel(plan().reference());
            assertThat(run.terminal()).isFalse();
            assertThat(request.get().body()).containsExactly((byte) '{', (byte) '}');
            assertThatThrownBy(() -> client.forget(run)).isInstanceOf(WorkerUnavailableException.class);
            assertThat(peer.requests()).isEqualTo(1);
        }
    }
}
