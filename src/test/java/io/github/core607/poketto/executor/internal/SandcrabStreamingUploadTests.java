package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual Unix transport with controlled peers; storage containment is verified by the native probe. */
class SandcrabStreamingUploadTests {
    private static final String ID = "3b45286e-07e5-4aa8-8ddc-99787b075f2e";
    private static final String EPOCH = "13289a65-c1e2-451c-97c9-07cba1a0f58f";
    private static final String SESSION = "7355a29f-fe96-4cd3-828e-d1e887bf3ef2";

    @TempDir
    Path temporary;

    @Test
    void aStagedFileCrossesTheMemoryCeilingWithItsExactLengthAndDigest() throws Exception {
        for (int bytes : new int[] {0, 2 * 1024 * 1024 + 17}) {
            Path file = temporary.resolve("source-" + bytes);
            SandcrabProtocol.UploadPlan plan = staged(file, bytes);
            try (var peer = new SandboxPeer(channel -> {
                Received received = receive(channel, plan);
                assertThat(received).isEqualTo(new Received(plan.bytes(), plan.sha256()));
                SandboxPeer.reply(channel, 200, receipt(plan));
            })) {
                var client = new SandcrabClient(peer.client(channel -> {}));
                assertThat(client.send(plan, file).succeeded()).isTrue();
                assertThat(Files.size(file)).isEqualTo(bytes);
                assertThat(peer.requests()).isEqualTo(1);
            }
        }
    }

    @Test
    void absentWrongLengthAndDirectorySourcesAreRefusedBeforeDispatch() throws Exception {
        Path file = temporary.resolve("original");
        SandcrabProtocol.UploadPlan plan = staged(file, 1024);
        Files.write(file, new byte[1]);
        try (var peer = new SandboxPeer(channel -> {
            throw new AssertionError("Invalid local source must not open a connection");
        })) {
            var client = new SandcrabClient(peer.client(channel -> {}));
            for (Path source : List.of(file, temporary, temporary.resolve("absent"))) {
                assertThatThrownBy(() -> client.send(plan, source))
                        .isInstanceOfSatisfying(
                                WorkerUnavailableException.class,
                                failure -> assertThat(failure.mayHaveExecuted()).isFalse());
            }
            assertThat(peer.requests()).isZero();
        }
    }

    @Test
    void aChangingSourceNeverCompletesARequestWithAnotherDeclaredLength() throws Exception {
        for (boolean truncate : List.of(true, false)) {
            Path file = temporary.resolve("changed-" + truncate);
            SandcrabProtocol.UploadPlan plan = staged(file, 1024);
            try (var peer = new SandboxPeer(channel -> {
                Received received = receive(channel, plan);
                assertThat(received.bytes()).isLessThan(plan.bytes());
            })) {
                var client = new SandcrabClient(peer.client(channel -> {
                    try {
                        if (truncate) {
                            Files.write(file, new byte[0]);
                        } else {
                            Files.write(file, new byte[1], StandardOpenOption.APPEND);
                        }
                    } catch (IOException failure) {
                        throw new UncheckedIOException("Could not change the test source", failure);
                    }
                }));
                assertThatThrownBy(() -> client.send(plan, file))
                        .isInstanceOfSatisfying(
                                WorkerUnavailableException.class,
                                failure -> assertThat(failure.mayHaveExecuted()).isTrue());
                assertThat(peer.requests()).isEqualTo(1);
            }
        }
    }

    @Test
    void aSuccessfulReceiptCannotHideChangedSourceBytes() throws Exception {
        Path file = temporary.resolve("changed-digest");
        SandcrabProtocol.UploadPlan plan = staged(file, 1024);
        try (var peer = new SandboxPeer(channel -> {
            Received received = receive(channel, plan);
            assertThat(received.sha256()).isNotEqualTo(plan.sha256());
            SandboxPeer.reply(channel, 200, receipt(plan));
        })) {
            var client = new SandcrabClient(peer.client(channel -> {
                try {
                    Files.write(file, new byte[1024]);
                } catch (IOException failure) {
                    throw new UncheckedIOException("Could not change the test source", failure);
                }
            }));
            assertThatThrownBy(() -> client.send(plan, file)).isInstanceOf(WorkerUnavailableException.class);
            assertThat(peer.requests()).isEqualTo(1);
        }
    }

    @Test
    void aLostFinalReplyIsInspectedWithoutResendingTheFile() throws Exception {
        Path file = temporary.resolve("lost-reply");
        SandcrabProtocol.UploadPlan plan = staged(file, 2 * 1024 * 1024);
        var writes = new AtomicInteger();
        try (var peer = new SandboxPeer(channel -> {
            if (writes.getAndIncrement() == 0) {
                assertThat(receive(channel, plan)).isEqualTo(new Received(plan.bytes(), plan.sha256()));
                return;
            }
            assertThat(SandboxPeer.request(channel).head()).startsWith("GET /v1/uploads/" + ID + " HTTP/1.1");
            SandboxPeer.reply(channel, 200, receipt(plan));
        })) {
            var client = new SandcrabClient(peer.client(channel -> {}));
            assertThatThrownBy(() -> client.send(plan, file))
                    .isInstanceOfSatisfying(
                            WorkerUnavailableException.class,
                            failure -> assertThat(failure.mayHaveExecuted()).isTrue());
            assertThat(client.upload(plan.reference()).succeeded()).isTrue();
            assertThat(peer.requests()).isEqualTo(2);
        }
    }

    private static SandcrabProtocol.UploadPlan staged(Path file, int bytes) throws IOException {
        byte[] block = new byte[4096];
        for (int index = 0; index < block.length; index++) {
            block[index] = (byte) index;
        }
        MessageDigest digest = SandcrabProtocol.digest();
        try (OutputStream output = Files.newOutputStream(file)) {
            for (int written = 0; written < bytes; ) {
                int size = Math.min(block.length, bytes - written);
                output.write(block, 0, size);
                digest.update(block, 0, size);
                written += size;
            }
        }
        return new SandcrabProtocol.UploadPlan(
                ID,
                EPOCH,
                SESSION,
                "repo.bundle",
                "raw",
                bytes,
                HexFormat.of().formatHex(digest.digest()),
                false,
                true,
                0600);
    }

    private static Received receive(SocketChannel channel, SandcrabProtocol.UploadPlan plan) throws IOException {
        InputStream input = Channels.newInputStream(channel);
        assertThat(SandboxPeer.head(input))
                .startsWith("PUT /v1/uploads/" + ID + "/content HTTP/1.1")
                .contains("Content-Length: " + plan.bytes() + "\r\n", "Content-Type: application/octet-stream\r\n");
        byte[] buffer = new byte[16384];
        MessageDigest digest = SandcrabProtocol.digest();
        long received = 0;
        while (received < plan.bytes()) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, plan.bytes() - received));
            if (read < 0) {
                break;
            }
            received += read;
            digest.update(buffer, 0, read);
        }
        return new Received(received, HexFormat.of().formatHex(digest.digest()));
    }

    private static String receipt(SandcrabProtocol.UploadPlan plan) {
        return """
                {"id":"%s","serverId":"%s","sessionId":"%s","profile":"python","volumeId":null,
                 "path":"repo.bundle","format":"raw","bytes":%s,"sha256":"%s","planDigest":"%s",
                 "state":"succeeded","code":null,"publication":"committed"}
                """.formatted(ID, EPOCH, SESSION, plan.bytes(), plan.sha256(), "a".repeat(64));
    }

    private record Received(long bytes, String sha256) {}
}
