package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class NativeVolumeDeadlineTests {
    @Test
    void anUnansweredImportedVolumeMutationIsBoundedAndSubmittedOnce() throws Exception {
        var release = new CountDownLatch(1);
        try (var peer = new SandboxPeer(channel -> {
            assertThat(SandboxPeer.request(channel).head()).startsWith("POST /v1/volumes HTTP/1.1");
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(failure);
            }
        })) {
            var client = new SandcrabClient(peer.client(channel -> {}));
            var plan = new SandcrabVolume.Plan(
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(), "repository", null, 8_388_608L, 256L);
            try {
                assertThatThrownBy(() -> client.createVolume(plan, Duration.ofMillis(120)))
                        .isInstanceOf(WorkerUnavailableException.class);
                assertThat(peer.requests()).isEqualTo(1);
            } finally {
                release.countDown();
            }
        }
    }
}
