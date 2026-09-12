package io.github.core607.poketto.executor.internal;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Verifies the frozen source before its final bytes can be acknowledged by original storage. */
final class CapturedBinaryInput extends InputStream {
    @FunctionalInterface
    interface Reader {
        byte[] read(long offset, int limit) throws IOException;
    }

    private final long size;
    private final String expected;
    private final Reader reader;
    private final MessageDigest digest;
    private byte[] buffer = new byte[0];
    private int cursor;
    private long consumed;
    private boolean closed;
    private boolean verified;

    CapturedBinaryInput(long size, String expected, Reader reader) {
        if (size < 1 || size > 128L * 1024 * 1024 || expected == null || !expected.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid binary capture metadata");
        }
        this.size = size;
        this.expected = expected;
        this.reader = Objects.requireNonNull(reader);
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (closed) {
            throw new IOException("binary capture input is closed");
        }
        if (length == 0) {
            return 0;
        }
        if (consumed == size) {
            return -1;
        }
        if (cursor == buffer.length) {
            int requested = (int) Math.min(65536, size - consumed);
            buffer = reader.read(consumed, requested);
            cursor = 0;
            if (buffer == null || buffer.length != requested) {
                closed = true;
                throw new IOException("binary capture chunk is incomplete");
            }
        }
        int count = Math.min(length, buffer.length - cursor);
        digest.update(buffer, cursor, count);
        consumed += count;
        if (consumed == size) {
            if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) {
                closed = true;
                throw new IOException("binary capture checksum differs");
            }
            verified = true;
        }
        System.arraycopy(buffer, cursor, bytes, offset, count);
        cursor += count;
        return count;
    }

    boolean verified() {
        return verified;
    }

    @Override
    public void close() {
        closed = true;
        buffer = new byte[0];
    }
}
