package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortableArchiveWriterTests {
    @TempDir
    Path root;

    private PortableArchiveWriter.Limits limits(long source, long zip) {
        return new PortableArchiveWriter.Limits(10, source, zip, Duration.ofSeconds(5));
    }

    private PortableArchiveWriter.Entry entry(String path, byte[] bytes) {
        return new PortableArchiveWriter.Entry(path, bytes.length, output -> output.write(bytes));
    }

    @Test
    void createsAReadableDiskArchiveWithExactTextAndBinaryBytes() throws Exception {
        byte[] text = "![image](../../media/picture.bin)\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] binary = new byte[] {0, -1, 1, 2};
        Path path = root.resolve("export.zip");
        try (var output = Files.newOutputStream(path)) {
            PortableArchiveWriter.write(
                    output,
                    List.of(entry("content/notes/a.md", text), entry("media/picture.bin", binary)),
                    limits(text.length + binary.length, 4096),
                    () -> {});
        }
        try (var zip = new ZipFile(path.toFile())) {
            assertThat(zip.size()).isEqualTo(2);
            assertThat(zip.getInputStream(zip.getEntry("content/notes/a.md")).readAllBytes())
                    .containsExactly(text);
            assertThat(zip.getInputStream(zip.getEntry("media/picture.bin")).readAllBytes())
                    .containsExactly(binary);
            assertThat(zip.getEntry(".poketto/assets.json")).isNull();
        }
    }

    @Test
    void rejectsCollisionsAndDeclaredBoundsBeforeWritingAnyZipBytes() {
        var output = new ByteArrayOutputStream();
        for (var entries : List.of(
                List.of(entry("a.md", new byte[1]), entry("A.md", new byte[1])),
                List.of(entry("a.md", new byte[1]), entry("a.md/b.md", new byte[1])),
                List.of(entry("big.md", new byte[11])))) {
            assertThatThrownBy(() -> PortableArchiveWriter.write(output, entries, limits(10, 4096), () -> {}))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(output.size()).isZero();
        }
        assertThatThrownBy(() -> entry("../outside", new byte[1])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry(".poketto/assets.json", new byte[1]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesMissingAndOversizedOriginalsAndCountsZipOverhead() {
        for (long declared : new long[] {0, 2}) {
            var entry = new PortableArchiveWriter.Entry("a.bin", declared, output -> output.write(1));
            assertThatThrownBy(() -> PortableArchiveWriter.write(
                            new ByteArrayOutputStream(), List.of(entry), limits(10, 4096), () -> {}))
                    .isInstanceOf(IOException.class);
        }
        assertThatThrownBy(() -> PortableArchiveWriter.write(
                        new ByteArrayOutputStream(), List.of(entry("a.bin", new byte[0])), limits(1, 20), () -> {}))
                .isInstanceOf(IOException.class);
    }

    @Test
    void rechecksAuthorizationDuringOneLargeOriginal() {
        var revoked = new AtomicBoolean();
        var source = new PortableArchiveWriter.Entry("a.bin", 131072, output -> {
            output.write(new byte[65536]);
            revoked.set(true);
            output.write(new byte[65536]);
        });
        assertThatThrownBy(() -> PortableArchiveWriter.write(
                        new ByteArrayOutputStream(), List.of(source), limits(131072, 262144), () -> {
                            if (revoked.get()) throw new SecurityException("revoked");
                        }))
                .isInstanceOf(SecurityException.class);
    }
}
