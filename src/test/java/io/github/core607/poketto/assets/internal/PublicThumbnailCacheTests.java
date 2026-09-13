package io.github.core607.poketto.assets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicThumbnailCacheTests {
    @TempDir
    Path directory;

    @Test
    void cacheHitsAreBoundToWorkspaceAndImmutableSource() throws Exception {
        var cache = new PublicThumbnailCache(directory.toRealPath().resolve("thumbnails"));
        var workspace = WorkspaceId.random();
        byte[] image = image();
        var renders = new AtomicInteger();

        assertThat(cache.get(workspace, "git:one", () -> rendered(image, renders)))
                .isEqualTo(image);
        assertThat(cache.get(workspace, "git:one", () -> rendered(image, renders)))
                .isEqualTo(image);
        assertThat(cache.get(workspace, "git:two", () -> rendered(image, renders)))
                .isEqualTo(image);
        assertThat(cache.get(WorkspaceId.random(), "git:one", () -> rendered(image, renders)))
                .isEqualTo(image);
        assertThat(renders).hasValue(3);
    }

    @Test
    void corruptAndDeletedEntriesRegenerateWithoutChangingSourceBytes() throws Exception {
        var root = directory.toRealPath().resolve("thumbnails");
        var cache = new PublicThumbnailCache(root);
        var workspace = WorkspaceId.random();
        byte[] image = image();
        var renders = new AtomicInteger();

        cache.get(workspace, "managed:one:rev", () -> rendered(image, renders));
        Path entry;
        try (var files = Files.list(root)) {
            entry = files.filter(path -> path.toString().endsWith(".thumb"))
                    .findFirst()
                    .orElseThrow();
        }
        Files.write(entry, new byte[] {1, 2, 3});
        assertThat(cache.get(workspace, "managed:one:rev", () -> rendered(image, renders)))
                .isEqualTo(image);
        Files.delete(entry);
        assertThat(cache.get(workspace, "managed:one:rev", () -> rendered(image, renders)))
                .isEqualTo(image);
        assertThat(renders).hasValue(3);
    }

    private static byte[] rendered(byte[] image, AtomicInteger renders) {
        renders.incrementAndGet();
        return image;
    }

    private static byte[] image() throws Exception {
        var source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try (var output = new ByteArrayOutputStream()) {
            assertThat(ImageIO.write(source, "png", output)).isTrue();
            return AlbumThumbnailRenderer.render(output.toByteArray());
        }
    }
}
