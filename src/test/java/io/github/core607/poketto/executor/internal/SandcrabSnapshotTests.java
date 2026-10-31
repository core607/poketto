package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real HTTP peers verify delivery and publication; native probes own sandbox containment. */
class SandcrabSnapshotTests {
    private static final String ID = "7355a29f-fe96-4cd3-828e-d1e887bf3ef2";
    private static final String EPOCH = "13289a65-c1e2-451c-97c9-07cba1a0f58f";
    private static final SandcrabProtocol.Session SESSION =
            new SandcrabProtocol.Session(ID, EPOCH, "python", "ready", 1000, null, null, null, null);

    @TempDir
    Path temporary;

    @Test
    void aSnapshotCannotWaitBeyondTheCallersRemainingBudgetOrPublishPartialBytes() throws Exception {
        Path target = temporary.resolve("timed-out");
        try (var peer = new SandboxPeer(channel -> {
            request(channel, "raw", "file.bin");
            assertThat(Channels.newInputStream(channel).read()).isEqualTo(-1);
        })) {
            var client = new SandcrabClient(peer.client(channel -> {}));
            long started = System.nanoTime();
            assertThatThrownBy(() -> client.snapshot(
                            SESSION,
                            new SandcrabFiles.Plan("file.bin", "raw", true),
                            1024,
                            target,
                            Duration.ofMillis(100),
                            () -> {}))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            assertThat(Files.exists(target)).isFalse();
            assertNoStagingFiles();
            assertThat(peer.requests()).isEqualTo(1);
        }
    }

    @Test
    void rawSnapshotsVerifyEmptyAndBinaryFilesAndKeepTheirVerifiedBytesPrivate() throws Exception {
        for (byte[] bytes : List.of(new byte[0], new byte[] {0, (byte) 255, 13, 10, 42})) {
            try (var peer = new SandboxPeer(channel -> {
                request(channel, "raw", "dir/file.bin");
                reply(channel, "application/octet-stream", bytes.length, digest(bytes), bytes);
            })) {
                var client = new SandcrabClient(peer.client(channel -> {}));
                SandcrabFiles.Content content = client.snapshot(SESSION, "dir/file.bin", bytes.length, () -> {});
                assertThat(content.bytes()).containsExactly(bytes);
                assertThat(content.sha256()).isEqualTo(digest(bytes));
                if (bytes.length > 0) {
                    content.bytes()[0] = 73;
                    assertThat(content.bytes()).containsExactly(bytes);
                }
                assertThat(peer.requests()).isEqualTo(1);
            }
        }
    }

    @Test
    void tarRootAndLargeRawDownloadsPublishOnlyVerifiedBytes() throws Exception {
        for (String format : List.of("raw", "tar")) {
            byte[] bytes = new byte[format.equals("raw") ? 2 * 1024 * 1024 + 17 : 1024];
            String path = format.equals("raw") ? "large.bin" : ".";
            String type = format.equals("raw") ? "application/octet-stream" : "application/x-tar";
            Path target = temporary.resolve(format);
            try (var peer = new SandboxPeer(channel -> {
                request(channel, format, path);
                reply(channel, type, bytes.length, digest(bytes), bytes);
            })) {
                var client = new SandcrabClient(peer.client(channel -> {}));
                SandcrabFiles.Snapshot result = client.snapshot(
                        SESSION, new SandcrabFiles.Plan(path, format, true), bytes.length, target, () -> {});
                assertThat(result.bytes()).isEqualTo(bytes.length);
                assertThat(result.sha256()).isEqualTo(digest(bytes));
                assertThat(Files.readAllBytes(target)).containsExactly(bytes);
                assertThat(peer.requests()).isEqualTo(1);
                assertNoStagingFiles();
            }
        }
    }

    @Test
    void cleanupFailureIsSuppressedWithoutReplacingTheOriginalRefusal() throws Exception {
        var refusal = new SandcrabClient.Rejected(403, "POLICY_CHANGED");
        var authorizations = new AtomicInteger();
        var residue = new AtomicReference<Path>();
        try (var peer = new SandboxPeer(channel -> {
            SandboxPeer.request(channel);
            reply(channel, "application/octet-stream", 0, digest(new byte[0]), new byte[0]);
        })) {
            var client = new SandcrabClient(peer.client(channel -> {}));
            assertThatThrownBy(() -> client.snapshot(
                            SESSION,
                            new SandcrabFiles.Plan("file", "raw", true),
                            0,
                            temporary.resolve("unpublished"),
                            () -> {
                                if (authorizations.incrementAndGet() == 3) {
                                    residue.set(obstructCleanup());
                                    throw refusal;
                                }
                            }))
                    .isSameAs(refusal);
            assertThat(refusal.getSuppressed()).singleElement().isInstanceOf(IOException.class);
            assertThat(residue.get().resolve("keep")).hasContent("unconfirmed cleanup");
            assertThat(temporary.resolve("unpublished")).doesNotExist();
        }
    }

    @Test
    void publicationFailureKeepsItsCauseAndCleanupFailureAndPreservesExistingDestination() throws Exception {
        var authorizations = new AtomicInteger();
        Path target = temporary.resolve("existing-at-publication");
        try (var peer = new SandboxPeer(channel -> {
            SandboxPeer.request(channel);
            reply(channel, "application/octet-stream", 0, digest(new byte[0]), new byte[0]);
        })) {
            var client = new SandcrabClient(peer.client(channel -> {}));
            assertThatThrownBy(() ->
                            client.snapshot(SESSION, new SandcrabFiles.Plan("file", "raw", true), 0, target, () -> {
                                if (authorizations.incrementAndGet() == 3) {
                                    obstructCleanup();
                                    try {
                                        Files.writeString(target, "keep existing destination");
                                    } catch (IOException failure) {
                                        throw new UncheckedIOException(failure);
                                    }
                                }
                            }))
                    .isInstanceOfSatisfying(WorkerUnavailableException.class, failure -> {
                        assertThat(failure.getCause()).isInstanceOf(IOException.class);
                        assertThat(failure.getCause().getSuppressed())
                                .singleElement()
                                .isInstanceOf(IOException.class);
                    });
            assertThat(target).hasContent("keep existing destination");
        }
    }

    /** A nonempty directory forces deletion failure on Windows and Linux without mocking filesystem calls. */
    private Path obstructCleanup() {
        try (var paths = Files.list(temporary)) {
            Path pending = paths.filter(path -> path.getFileName().toString().startsWith(".sandcrab-"))
                    .findFirst()
                    .orElseThrow();
            Files.delete(pending);
            Files.createDirectory(pending);
            Files.writeString(pending.resolve("keep"), "unconfirmed cleanup");
            return pending;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Test
    void malformedPathsAndConsumerBoundsFailBeforeDispatch() throws Exception {
        for (String path : List.of(
                "",
                ".",
                "..",
                "../file",
                "/file",
                "dir//file",
                "dir/./file",
                "dir/../file",
                "dir\\file",
                "file\n",
                "a".repeat(256),
                "中".repeat(86),
                "a/".repeat(64) + "a")) {
            assertThatThrownBy(() -> new SandcrabFiles.Plan(path, "raw", true))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new SandcrabFiles.Plan("file", "zip", true))
                .isInstanceOf(IllegalArgumentException.class);
        try (var peer = new SandboxPeer(channel -> {
            throw new AssertionError("Invalid bounds must not dispatch a snapshot");
        })) {
            var client = new SandcrabClient(peer.client(channel -> {}));
            for (int bound : new int[] {-1, 8 * 1024 * 1024 + 1}) {
                assertThatThrownBy(() -> client.snapshot(SESSION, "file", bound, () -> {}))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            for (long bound : new long[] {-1, 1_073_741_825L}) {
                assertThatThrownBy(() -> client.snapshot(
                                SESSION,
                                new SandcrabFiles.Plan("file", "raw", true),
                                bound,
                                temporary.resolve("invalid-bound"),
                                () -> {}))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(peer.requests()).isZero();
            assertNoStagingFiles();
        }
    }

    @Test
    void excessLengthTruncationAndDigestMismatchNeverPublishOrRetry() throws Exception {
        byte[] bytes = new byte[] {1, 2, 3};
        for (String fault : List.of("bound", "truncation", "digest")) {
            Path target = temporary.resolve(fault);
            try (var peer = new SandboxPeer(channel -> {
                SandboxPeer.request(channel);
                reply(
                        channel,
                        "application/octet-stream",
                        fault.equals("truncation") ? 4 : 3,
                        fault.equals("digest") ? "a".repeat(64) : digest(bytes),
                        bytes);
            })) {
                var client = new SandcrabClient(peer.client(channel -> {}));
                assertThatThrownBy(() -> client.snapshot(
                                SESSION,
                                new SandcrabFiles.Plan("file", "raw", true),
                                fault.equals("bound") ? 2 : 4,
                                target,
                                () -> {}))
                        .isInstanceOf(WorkerUnavailableException.class);
                assertThat(target).doesNotExist();
                assertNoStagingFiles();
                assertThat(peer.requests()).isEqualTo(1);
            }
        }
    }

    @Test
    void aSnapshotRequiresAnUnambiguousLengthFormatAndStrongDigest() throws Exception {
        String valid = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 0\r\n"
                + "X-Sandcrab-Server-Id: " + EPOCH + "\r\n"
                + "ETag: \"" + digest(new byte[0]) + "\"\r\n";
        for (String head : List.of(
                valid.replace("Content-Length: 0\r\n", ""),
                valid + "Content-Length: 0\r\n",
                valid + "Transfer-Encoding: chunked\r\n",
                valid.replace("application/octet-stream", "application/x-tar"),
                valid.replace("ETag: \"", "ETag: W/\""),
                valid + "ETag: \"" + "a".repeat(64) + "\"\r\n",
                valid.replace(digest(new byte[0]), "bad"),
                valid.substring(0, valid.indexOf("ETag:")))) {
            try (var peer = new SandboxPeer(channel -> {
                SandboxPeer.request(channel);
                Channels.newOutputStream(channel).write((head + "\r\n").getBytes(StandardCharsets.US_ASCII));
            })) {
                var client = new SandcrabClient(peer.client(channel -> {}));
                assertThatThrownBy(() -> client.snapshot(SESSION, "file", 0, () -> {}))
                        .isInstanceOf(WorkerUnavailableException.class);
                assertThat(peer.requests()).isEqualTo(1);
            }
        }
    }

    @Test
    void missingWrongAndDuplicateEpochsCannotPublishOtherwiseValidBytes() throws Exception {
        byte[] bytes = new byte[] {1, 2, 3};
        for (String epoch : List.of(
                "",
                "X-Sandcrab-Server-Id: " + ID + "\r\n",
                "X-Sandcrab-Server-Id: " + EPOCH + "\r\nX-Sandcrab-Server-Id: " + EPOCH + "\r\n")) {
            Path target = temporary.resolve("wrong-epoch");
            try (var peer = new SandboxPeer(channel -> {
                SandboxPeer.request(channel);
                String head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 3\r\n"
                        + "ETag: \"" + digest(bytes) + "\"\r\n" + epoch + "\r\n";
                var packet = new ByteArrayOutputStream();
                packet.write(head.getBytes(StandardCharsets.US_ASCII));
                packet.write(bytes);
                Channels.newOutputStream(channel).write(packet.toByteArray());
            })) {
                var client = new SandcrabClient(peer.client(channel -> {}));
                assertThatThrownBy(() -> client.snapshot(
                                SESSION, new SandcrabFiles.Plan("file", "raw", true), bytes.length, target, () -> {}))
                        .isInstanceOf(WorkerUnavailableException.class);
                assertThat(target).doesNotExist();
                assertNoStagingFiles();
                assertThat(peer.requests()).isEqualTo(1);
            }
        }
    }

    @Test
    void validRefusalsKeepTheirStatusWhileServerAndMalformedErrorsStayUnavailable() throws Exception {
        for (int status : new int[] {400, 404, 409, 503}) {
            try (var peer = new SandboxPeer(channel -> {
                SandboxPeer.request(channel);
                SandboxPeer.reply(channel, status, "{\"error\":{\"code\":\"FILE_NOT_FOUND\"}}");
            })) {
                var client = new SandcrabClient(peer.client(channel -> {}));
                if (status < 500) {
                    assertThatThrownBy(() -> client.snapshot(SESSION, "file", 8, () -> {}))
                            .isInstanceOfSatisfying(SandcrabClient.Rejected.class, failure -> {
                                assertThat(failure.status()).isEqualTo(status);
                                assertThat(failure.code()).isEqualTo("FILE_NOT_FOUND");
                            });
                } else {
                    assertThatThrownBy(() -> client.snapshot(SESSION, "file", 8, () -> {}))
                            .isInstanceOf(WorkerUnavailableException.class);
                }
                assertThat(peer.requests()).isEqualTo(1);
            }
        }
        for (String body : List.of("{}", "{\"error\":{\"code\":\"invalid code\"}}")) {
            try (var peer = new SandboxPeer(channel -> {
                SandboxPeer.request(channel);
                SandboxPeer.reply(channel, 404, body);
            })) {
                var client = new SandcrabClient(peer.client(channel -> {}));
                assertThatThrownBy(() -> client.snapshot(SESSION, "file", 8, () -> {}))
                        .isInstanceOf(WorkerUnavailableException.class);
            }
        }
    }

    @Test
    void authorizationRevokedBeforeDispatchDoesNotConnect() throws Exception {
        try (var peer = new SandboxPeer(channel -> {
            throw new AssertionError("A revoked grant must not dispatch a snapshot");
        })) {
            var client = new SandcrabClient(peer.client(channel -> {}));
            assertThatThrownBy(() -> client.snapshot(SESSION, "file", 8, SandcrabSnapshotTests::revoked))
                    .isInstanceOf(SecurityException.class)
                    .hasMessage("Snapshot grant revoked");
            assertThat(peer.requests()).isZero();
        }
    }

    @Test
    void authorizationRevokedDuringOrAfterDeliveryRemovesStagingWithoutPublishing() throws Exception {
        for (boolean afterDelivery : List.of(false, true)) {
            byte[] bytes = new byte[16384];
            Path target = temporary.resolve("revoked-" + afterDelivery);
            var observedRevocation = new AtomicBoolean();
            var cancelled = new CountDownLatch(1);
            try (var peer = new SandboxPeer(channel -> {
                SandboxPeer.request(channel);
                reply(
                        channel,
                        "application/octet-stream",
                        bytes.length,
                        digest(bytes),
                        afterDelivery ? bytes : Arrays.copyOf(bytes, 16));
                if (!afterDelivery) {
                    awaitCancellation(cancelled);
                }
            })) {
                var client = new SandcrabClient(peer.client(channel -> {}));
                Runnable authorize = () -> {
                    long staged = stagedBytes();
                    if (afterDelivery ? staged == bytes.length : staged > 0 && staged < bytes.length) {
                        observedRevocation.set(true);
                        cancelled.countDown();
                        revoked();
                    }
                };
                assertThatThrownBy(() -> client.snapshot(
                                SESSION, new SandcrabFiles.Plan("file", "raw", true), bytes.length, target, authorize))
                        .isInstanceOf(SecurityException.class);
                assertThat(observedRevocation).isTrue();
                assertThat(target).doesNotExist();
                assertNoStagingFiles();
                assertThat(peer.requests()).isEqualTo(1);
            }
        }
    }

    @Test
    void anExistingDestinationIsNeverReplacedEvenWhenItAppearsDuringTheDownload() throws Exception {
        for (boolean duringDownload : List.of(false, true)) {
            Path target = temporary.resolve("existing-" + duringDownload);
            if (!duringDownload) {
                Files.writeString(target, "retained work");
            }
            try (var peer = new SandboxPeer(channel -> {
                SandboxPeer.request(channel);
                Files.writeString(target, "retained work");
                reply(channel, "application/octet-stream", 0, digest(new byte[0]), new byte[0]);
            })) {
                var client = new SandcrabClient(peer.client(channel -> {}));
                assertThatThrownBy(() -> client.snapshot(
                                SESSION, new SandcrabFiles.Plan("file", "raw", true), 0, target, () -> {}))
                        .isInstanceOf(WorkerUnavailableException.class);
                assertThat(Files.readString(target)).isEqualTo("retained work");
                assertNoStagingFiles();
                assertThat(peer.requests()).isEqualTo(duringDownload ? 1 : 0);
            }
        }
    }

    private long stagedBytes() {
        try (var paths = Files.list(temporary)) {
            Path staged = paths.filter(p -> p.getFileName().toString().startsWith(".sandcrab-"))
                    .findFirst()
                    .orElse(null);
            return staged == null ? 0 : Files.size(staged);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not inspect the staged snapshot", failure);
        }
    }

    private void assertNoStagingFiles() throws IOException {
        try (var paths = Files.list(temporary)) {
            assertThat(paths.filter(p -> p.getFileName().toString().startsWith(".sandcrab-"))
                            .toList())
                    .isEmpty();
        }
    }

    private static void revoked() {
        throw new SecurityException("Snapshot grant revoked");
    }

    private static void awaitCancellation(CountDownLatch cancelled) throws IOException {
        try {
            if (!cancelled.await(2, TimeUnit.SECONDS)) {
                throw new IOException("Authorization did not stop the partial download");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while observing download cancellation", failure);
        }
    }

    private static void request(SocketChannel channel, String format, String path) throws IOException {
        SandboxPeer.Request request = SandboxPeer.request(channel);
        assertThat(request.head()).startsWith("POST /v1/sessions/" + ID + "/snapshot HTTP/1.1\r\n");
        SandcrabFiles.Plan plan = SandcrabProtocol.read(request.body(), SandcrabFiles.Plan.class);
        assertThat(plan).isEqualTo(new SandcrabFiles.Plan(path, format, true));
    }

    private static String digest(byte[] bytes) {
        return HexFormat.of().formatHex(SandcrabProtocol.digest().digest(bytes));
    }

    private static void reply(SocketChannel channel, String type, long length, String digest, byte[] bytes)
            throws IOException {
        String head = "HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + length + "\r\nETag: \""
                + digest + "\"\r\nX-Sandcrab-Server-Id: " + EPOCH + "\r\n\r\n";
        var packet = new ByteArrayOutputStream();
        packet.write(head.getBytes(StandardCharsets.US_ASCII));
        packet.write(bytes);
        Channels.newOutputStream(channel).write(packet.toByteArray());
    }
}
