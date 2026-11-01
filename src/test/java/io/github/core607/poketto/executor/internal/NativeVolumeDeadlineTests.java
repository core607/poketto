package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Real Unix exchanges with a synthetic peer; these tests do not claim native allocation or isolation. */
class NativeVolumeDeadlineTests {
    @Test
    void anUnansweredCreationIsBoundedWithoutRetryingItsUnknownOutcome() throws Exception {
        var release = new CountDownLatch(1);
        try (var peer = new SandboxPeer(channel -> {
            assertThat(SandboxPeer.request(channel).head()).startsWith("POST /v1/volumes HTTP/1.1");
            await(release);
        })) {
            NativeRepositoryLease lease = lease(peer);
            try {
                boundedFailure(lease);
                assertThat(lease.volumeMayExist()).isTrue();
                assertThat(peer.requests()).isEqualTo(1);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void anUnansweredReadinessLookupSharesTheCreationDeadline() throws Exception {
        var release = new CountDownLatch(1);
        try (var peer = new SandboxPeer(channel -> {
            SandboxPeer.Request request = SandboxPeer.request(channel);
            if (request.head().startsWith("POST ")) {
                SandcrabVolume.Plan plan = SandcrabProtocol.read(request.body(), SandcrabVolume.Plan.class);
                var volume = new SandcrabVolume(
                        plan.id(),
                        plan.profile(),
                        "creating",
                        null,
                        plan.byteLimit(),
                        plan.inodeLimit(),
                        null,
                        "c".repeat(64),
                        false,
                        null);
                SandboxPeer.reply(channel, 202, new String(SandcrabProtocol.write(volume), StandardCharsets.UTF_8));
            } else {
                assertThat(request.head()).startsWith("GET /v1/volumes/");
                await(release);
            }
        })) {
            NativeRepositoryLease lease = lease(peer);
            try {
                boundedFailure(lease);
                assertThat(lease.volumeMayExist()).isTrue();
                assertThat(peer.requests()).isEqualTo(2);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void repeatedDefiniteRefusalsDoNotRenewTheCreationDeadline() throws Exception {
        try (var peer = new SandboxPeer(channel -> {
            assertThat(SandboxPeer.request(channel).head()).startsWith("POST /v1/volumes HTTP/1.1");
            try {
                SandboxPeer.reply(channel, 429, "{\"error\":{\"code\":\"STORAGE_CAPACITY\"}}");
            } catch (IOException deadlineClosedPeer) {
                // The caller may reach its deadline while this last reply is still being written.
            }
        })) {
            NativeRepositoryLease lease = lease(peer);
            boundedFailure(lease);
            assertThat(peer.requests()).isGreaterThan(1);
        }
    }

    private static void boundedFailure(NativeRepositoryLease lease) {
        long started = System.nanoTime();
        assertThatThrownBy(() -> lease.createVolume(Duration.ofMillis(300)))
                .isInstanceOf(WorkerUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isGreaterThanOrEqualTo(Duration.ofMillis(300))
                .isLessThan(Duration.ofSeconds(2));
    }

    private static NativeRepositoryLease lease(SandboxPeer peer) {
        var storage = new AccountCopyRecord.NativeStorage(UUID.randomUUID(), "repository", 8_388_608, 256);
        var writer = new AccountCopyRecord.Writer(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), storage);
        return new NativeRepositoryLease(
                new SandcrabClient(peer.client(channel -> {})), storage, writer, 120000, 65536, 8_388_608, () -> {});
    }

    private static void await(CountDownLatch release) throws IOException {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IOException("Test did not release its waiting peer");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Test peer was interrupted", interrupted);
        }
    }
}
