package io.github.core607.poketto.assets.internal;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedImageReadsTests {
    @TempDir
    Path directory;

    @Test
    void fileReadsPreserveShortExactAndSentinelBoundaries() throws Exception {
        Path file = directory.resolve("bounded.bin");
        int maximum = 32769;
        for (int length : new int[] {0, 1, 8191, 8192, 8193, maximum - 1, maximum, maximum + 1}) {
            byte[] bytes = new byte[length];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
            Files.write(file, bytes);
            try (var input = Files.newInputStream(file)) {
                assertThat(BoundedImageReads.read(input, maximum))
                        .isEqualTo(java.util.Arrays.copyOf(bytes, Math.min(length, maximum)));
                assertThat(input.read()).isEqualTo(length > maximum ? bytes[maximum] & 255 : -1);
            }
        }
    }

    @Test
    void everyUnderlyingReadIsSmallAndShortOrZeroReadsStillMakeProgress() throws Exception {
        byte[] bytes = new byte[20000];
        java.util.Arrays.fill(bytes, (byte) 7);
        var input = new ByteArrayInputStream(bytes) {
            private boolean zero = true;

            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                assertThat(length).isBetween(1, 8192);
                if (zero) {
                    zero = false;
                    return 0;
                }
                return super.read(buffer, offset, Math.min(length, 13));
            }
        };
        assertThat(BoundedImageReads.read(input, bytes.length + 1)).isEqualTo(bytes);
        assertThatThrownBy(() -> BoundedImageReads.read(
                        new InputStream() {
                            @Override
                            public int read() throws IOException {
                                throw new IOException("synthetic read failure");
                            }
                        },
                        100))
                .isInstanceOf(IOException.class);
    }
}
