package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeTransferStoreTests {
    @TempDir
    Path temporary;

    private Path root;

    @BeforeEach
    void canonicalRoot() throws Exception {
        root = temporary.toRealPath().resolve("transfers");
    }

    @Test
    void oversizedAndInterruptedSourcesReleaseTheirPrivateFilesAndReservations() throws Exception {
        try (var store = new NativeTransferStore(root, 4, 1)) {
            assertThatThrownBy(() -> store.write(4, () -> {}, output -> output.write(new byte[5])))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThatThrownBy(() -> store.write(4, () -> {}, output -> {
                        output.write(new byte[2]);
                        throw new IOException("synthetic interrupted source");
                    }))
                    .isInstanceOf(WorkerUnavailableException.class);
            try (var file = store.write(4, () -> {}, output -> output.write(new byte[] {1, 2, 3, 4}))) {
                assertThat(file.size()).isEqualTo(4);
                assertThat(file.sha256()).isEqualTo(SessionWorker.hash(new byte[] {1, 2, 3, 4}));
                assertThatThrownBy(() -> store.write(1, () -> {}, output -> output.write(0)))
                        .isInstanceOf(NativeTransferStore.Capacity.class);
            }
            try (var paths = Files.list(root)) {
                assertThat(paths.map(path -> path.getFileName().toString())).containsExactly(".lock");
            }
        }
    }

    @Test
    void oneOwnerKeepsTheStoreLockedUntilAnActiveProducerHasStopped() throws Exception {
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var outcome = new CompletableFuture<Throwable>();
        var store = new NativeTransferStore(root, 8, 2);
        Thread producer = Thread.ofVirtual().start(() -> {
            try {
                store.write(8, () -> {}, output -> {
                    started.countDown();
                    try {
                        if (!finish.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("source was not released");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("source interrupted", interrupted);
                    }
                    output.write(new byte[] {1, 2});
                });
                outcome.complete(null);
            } catch (Throwable failure) {
                outcome.complete(failure);
            }
        });
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            store.close();
            assertThatThrownBy(() -> new NativeTransferStore(root, 8, 2)).isInstanceOf(RetainedCopyException.class);
        } finally {
            finish.countDown();
            producer.join(5000);
            store.close();
        }
        assertThat(outcome.get(5, TimeUnit.SECONDS)).isInstanceOf(WorkerUnavailableException.class);
        try (var reopened = new NativeTransferStore(root, 8, 2);
                var file = reopened.write(2, () -> {}, output -> output.write(new byte[] {3, 4}))) {
            assertThat(file.size()).isEqualTo(2);
        }
    }

    @Test
    void startupDoesNotDeleteUnrecognizedFiles() throws Exception {
        Files.createDirectory(root);
        Path protectedFile = Files.writeString(root.resolve("operator-notes.txt"), "keep me");
        assertThatThrownBy(() -> new NativeTransferStore(root, 8, 2)).isInstanceOf(WorkerUnavailableException.class);
        assertThat(Files.readString(protectedFile)).isEqualTo("keep me");
    }

    @Test
    void interruptedSnapshotTemporaryRemainsInsideTheChargedCaptureAndIsCleaned() throws Exception {
        try (var store = new NativeTransferStore(root, 4, 1)) {
            assertThatThrownBy(() -> store.capture(4, () -> {}, target -> {
                        Path pending = Files.createTempFile(target.getParent(), ".sandcrab-", ".snapshot");
                        Files.write(pending, new byte[] {1, 2, 3});
                        throw new IOException("snapshot body interrupted");
                    }))
                    .isInstanceOf(WorkerUnavailableException.class);
            try (var paths = Files.list(root)) {
                assertThat(paths.map(path -> path.getFileName().toString())).containsExactly(".lock");
            }
            try (var file = store.write(4, () -> {}, output -> output.write(new byte[4]))) {
                assertThat(file.size()).isEqualTo(4);
            }
        }
    }
}
