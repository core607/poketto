package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SandboxChunksTests {
    @Test
    void fragmentedSizeDelimitersAndBinaryPayloadDecodeAcrossChunkBoundaries() throws Exception {
        byte[] bytes = new byte[] {
            '2',
            '\r',
            '\n',
            0,
            (byte) 255,
            '\r',
            '\n',
            '3',
            '\r',
            '\n',
            'a',
            'b',
            'c',
            '\r',
            '\n',
            '0',
            '\r',
            '\n',
            '\r',
            '\n'
        };
        try (var chunks = new SandboxChunks(new Fragmented(bytes))) {
            assertThat(chunks.read()).isZero();
            assertThat(chunks.read()).isEqualTo(255);
            assertThat(chunks.readAllBytes()).containsExactly((byte) 'a', (byte) 'b', (byte) 'c');
            assertThat(chunks.read()).isEqualTo(-1);
            assertThat(chunks.read(new byte[4])).isEqualTo(-1);
        }
    }

    @Test
    void emptyTerminationAndZeroLengthReadsPreserveTheFollowingMessage() throws Exception {
        var source = new ByteArrayInputStream("0\r\n\r\nnext".getBytes(StandardCharsets.US_ASCII));
        try (var chunks = new SandboxChunks(source)) {
            assertThat(chunks.read(new byte[0])).isZero();
            assertThat(source.available()).isEqualTo(9);
            assertThat(chunks.read()).isEqualTo(-1);
            assertThat(chunks.read(new byte[3], 1, 0)).isZero();
            assertThat(chunks.read()).isEqualTo(-1);
            assertThat(new String(source.readAllBytes(), StandardCharsets.US_ASCII))
                    .isEqualTo("next");
        }
    }

    @Test
    void exactChunkByteBoundIsAcceptedWithoutCombiningTheFollowingChunk() throws Exception {
        byte[] bytes = new byte[131072];
        var wire = new ByteArrayOutputStream();
        wire.write("20000\r\n".getBytes(StandardCharsets.US_ASCII));
        wire.write(bytes);
        wire.write("\r\n1\r\nx\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        try (var chunks = new SandboxChunks(new ByteArrayInputStream(wire.toByteArray()))) {
            assertThat(chunks.readNBytes(bytes.length)).containsExactly(bytes);
            assertThat(chunks.read()).isEqualTo('x');
            assertThat(chunks.read()).isEqualTo(-1);
        }
    }

    @Test
    void oversizedSizesExtensionsTrailersAndMalformedFramingAreRejected() {
        for (String wire : List.of(
                "20001\r\n",
                "FFFFFFFF\r\n",
                "000000001\r\n",
                "-1\r\n",
                "+1\r\n",
                " 1\r\n",
                "g\r\n",
                "\r\n",
                "1\n",
                "1;key=value\r\nx\r\n0\r\n\r\n",
                "1\r\nx\n0\r\n\r\n",
                "0\r\nTrailer: value\r\n\r\n")) {
            try (var chunks = new SandboxChunks(input(wire))) {
                assertThatThrownBy(chunks::readAllBytes)
                        .as("wire framing %s", wire)
                        .isInstanceOf(IOException.class);
            } catch (IOException failure) {
                throw new AssertionError("Closing a test byte stream must not fail", failure);
            }
        }
    }

    @Test
    void endOfInputCannotSubstituteForPayloadDelimiterOrTheFinalChunk() {
        for (String wire :
                List.of("", "1", "1\r", "1\r\n", "2\r\nx", "1\r\nx", "1\r\nx\r", "1\r\nx\r\n", "0\r\n", "0\r\n\r")) {
            try (var chunks = new SandboxChunks(input(wire))) {
                assertThatThrownBy(chunks::readAllBytes)
                        .as("truncated wire %s", wire)
                        .isInstanceOf(IOException.class);
            } catch (IOException failure) {
                throw new AssertionError("Closing a test byte stream must not fail", failure);
            }
        }
    }

    private static InputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static final class Fragmented extends ByteArrayInputStream {
        Fragmented(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            return super.read(bytes, offset, Math.min(length, 1));
        }
    }
}
