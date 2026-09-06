package io.github.core607.poketto.assets.internal;

import static org.assertj.core.api.Assertions.*;

import io.github.core607.poketto.content.RepositoryBlob;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectInserter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryImageCacheTests {
    @TempDir
    Path directory;

    @Test
    void maximumCacheHitIsExactAndTruncatedOrOversizedCacheFilesAreRebuilt() throws Exception {
        directory = directory.toRealPath();
        byte[] original = LocalManagedBlobStoreTests.paddedPng(RepositoryBlobReader.MAX_BLOB_BYTES);
        var cache = new RepositoryImageCache(directory.resolve("cache"), RepositoryBlobReader.MAX_BLOB_BYTES);
        RepositoryBlob blob;
        try (var formatter = new ObjectInserter.Formatter()) {
            blob = new RepositoryBlob(
                    WorkspaceId.random(),
                    "a".repeat(40),
                    "image.png",
                    formatter.idFor(Constants.OBJ_BLOB, original).name(),
                    original.length,
                    true);
        }
        var reads = new AtomicInteger();
        java.util.function.Supplier<byte[]> loader = () -> {
            reads.incrementAndGet();
            return original;
        };
        assertThat(cache.get(blob, loader).bytes()).isEqualTo(original);
        assertThat(cache.get(blob, loader).bytes()).isEqualTo(original);
        assertThat(reads.get()).isOne();
        Path file;
        try (var entries = Files.list(directory.resolve("cache"))) {
            file = entries.filter(path -> path.toString().endsWith(".image"))
                    .findFirst()
                    .orElseThrow();
        }
        try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(original.length - 1L);
        }
        assertThat(cache.get(blob, loader).bytes()).isEqualTo(original);
        assertThat(reads.get()).isEqualTo(2);
        Files.write(file, new byte[] {1}, StandardOpenOption.APPEND);
        assertThat(cache.get(blob, loader).bytes()).isEqualTo(original);
        assertThat(reads.get()).isEqualTo(3);
        assertThat(Files.size(file)).isEqualTo(original.length);
    }
}
