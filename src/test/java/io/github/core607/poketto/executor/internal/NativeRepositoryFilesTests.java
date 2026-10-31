package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeRepositoryFilesTests {
    @TempDir
    Path temporary;

    @Test
    void stagingStopsAtTheCommandDeadlineBeforeAnyUploadIsDispatched() throws Exception {
        var lease = mock(NativeRepositoryLease.class);
        var elapsed = new AtomicBoolean();
        when(lease.remaining()).thenAnswer(call -> {
            if (elapsed.get()) {
                throw new WorkerUnavailableException();
            }
            return Duration.ofSeconds(1);
        });
        Path root = temporary.toRealPath().resolve("transfers");
        try (var storage = new NativeTransferStore(root, 16, 2)) {
            var files = new NativeRepositoryFiles(storage, (runtime, path, maximum, target, authorize) -> {});
            assertThatThrownBy(() -> files.install(lease, "export.zip", 2, "a".repeat(64), output -> {
                        output.write(1);
                        elapsed.set(true);
                        output.write(2);
                    }))
                    .isInstanceOf(WorkerUnavailableException.class);
            verify(lease, never()).upload(anyString(), any());
            try (var entries = Files.list(root)) {
                assertThat(entries.map(path -> path.getFileName().toString())).containsExactly(".lock");
            }
        }
    }

    @Test
    void identicalLargePackagesUseTheirOwnDeclaredSizeRatherThanTheArtifactFileLimit() {
        // Transport bytes are tested separately; this peer models a ZIP above the artifact limit.
        long size = NativeArtifacts.MAX_FILE + 1;
        var storage = mock(NativeTransferStore.class);
        var lease = mock(NativeRepositoryLease.class);
        var staged = mock(NativeTransferStore.File.class);
        when(staged.size()).thenReturn(size);
        when(staged.sha256()).thenReturn("a".repeat(64));
        when(storage.write(anyLong(), any(), any())).thenReturn(staged);
        when(storage.capture(anyLong(), any(), any())).thenAnswer(call -> {
            if ((long) call.getArgument(0) < size) {
                throw new SandcrabClient.Rejected(400, "FILE_LIMIT");
            }
            return staged;
        });
        when(lease.upload(anyString(), any()))
                .thenReturn(new SandcrabProtocol.Upload(
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        "repository",
                        null,
                        NativeRepositoryLayout.repository("export.zip"),
                        "raw",
                        size,
                        "a".repeat(64),
                        "b".repeat(64),
                        "failed",
                        "PATH_EXISTS",
                        "none"));
        var files = new NativeRepositoryFiles(storage, (runtime, path, maximum, target, authorize) -> {});
        assertThat(files.install(lease, "export.zip", size, "a".repeat(64), output -> {}))
                .isTrue();
        verify(storage).capture(eq(size), any(), any());
    }
}
