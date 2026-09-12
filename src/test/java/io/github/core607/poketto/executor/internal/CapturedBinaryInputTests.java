package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CapturedBinaryInputTests {
    @Test
    void streamsBoundedChunksAndVerifiesBeforeReportingCompleteInput() throws Exception {
        byte[] source = new byte[200000];
        for (int i = 0; i < source.length; i++) {
            source[i] = (byte) (i % 251);
        }
        var calls = new AtomicInteger();
        try (var input = new CapturedBinaryInput(source.length, hash(source), (offset, limit) -> {
            calls.incrementAndGet();
            assertThat(limit).isBetween(1, 65536);
            return Arrays.copyOfRange(source, (int) offset, (int) offset + limit);
        })) {
            assertThat(input.readAllBytes()).isEqualTo(source);
            assertThat(input.verified()).isTrue();
            assertThat(input.read()).isEqualTo(-1);
            assertThat(calls).hasValue(4);
        }
    }

    @Test
    void corruptionAndTruncationCannotProduceAnAcknowledgedEndOfInput() throws Exception {
        byte[] source = {0, 1, 2, 3};
        try (var corrupt = new CapturedBinaryInput(4, hash(source), (offset, limit) -> new byte[] {0, 1, 2, 4})) {
            assertThatThrownBy(corrupt::readAllBytes).isInstanceOf(IOException.class);
            assertThat(corrupt.verified()).isFalse();
            assertThatThrownBy(corrupt::read).isInstanceOf(IOException.class);
        }
        try (var truncated = new CapturedBinaryInput(4, hash(source), (offset, limit) -> new byte[3])) {
            assertThatThrownBy(truncated::readAllBytes).isInstanceOf(IOException.class);
            assertThat(truncated.verified()).isFalse();
        }
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
