package io.github.core607.poketto.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MediaPlaybackTests {
    @Test
    void recognizedContainersPreserveRangesAcrossSingleByteAndBlockWrites() throws Exception {
        for (MediaPlayback type : MediaPlayback.values()) {
            byte[] bytes = container(type);
            var output = new ByteArrayOutputStream();
            var selected = type.select(output, bytes.length, 4000, 1500);
            for (int index = 0; index < 4100; index++) {
                selected.write(bytes[index]);
            }
            selected.write(bytes, 4100, bytes.length - 4100);
            assertThat(output.toByteArray()).containsExactly(Arrays.copyOfRange(bytes, 4000, 5500));
        }
    }

    @Test
    void invalidContainersAndMetadataNeverProducePlaybackBytes() {
        for (MediaPlayback type : MediaPlayback.values()) {
            for (byte[] bytes : new byte[][] {
                new byte[1], "<html>active content</html>".getBytes(StandardCharsets.UTF_8), new byte[8192]
            }) {
                var output = new ByteArrayOutputStream();
                var selected = type.select(output, bytes.length, 0, bytes.length);
                assertThatThrownBy(() -> selected.write(bytes)).isInstanceOf(IllegalArgumentException.class);
                assertThat(output.size()).isZero();
            }
        }
        assertThat(MediaPlayback.forType("text/html")).isEmpty();
        assertThat(MediaPlayback.forType("audio/wave")).contains(MediaPlayback.WAV);
        byte[] truncated = container(MediaPlayback.MP4_VIDEO);
        ByteBuffer.wrap(truncated).putInt(100_000);
        var output = new ByteArrayOutputStream();
        assertThatThrownBy(() -> MediaPlayback.MP4_VIDEO
                        .select(output, truncated.length, 4096, 1)
                        .write(truncated))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(output.size()).isZero();
    }

    private static byte[] container(MediaPlayback type) {
        byte[] bytes = new byte[8192];
        Arrays.fill(bytes, (byte) 42);
        switch (type) {
            case MP3 -> put(bytes, 0, "ID3");
            case WAV -> {
                put(bytes, 0, "RIFF");
                put(bytes, 8, "WAVE");
            }
            case MP4_AUDIO, MP4_VIDEO -> {
                ByteBuffer.wrap(bytes).putInt(24);
                put(bytes, 4, "ftypisom");
                put(bytes, 16, "mp42");
            }
            case WEBM_AUDIO, WEBM_VIDEO -> {
                ByteBuffer.wrap(bytes).putInt(0x1a45dfa3);
                bytes[10] = 0x42;
                bytes[11] = (byte) 0x82;
                bytes[12] = (byte) 0x84;
                put(bytes, 13, "webm");
            }
        }
        return bytes;
    }

    private static void put(byte[] target, int offset, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }
}
