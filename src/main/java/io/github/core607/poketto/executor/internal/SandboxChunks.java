package io.github.core607.poketto.executor.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Decodes the native event transport; event framing and total payload budgets belong to its reader. */
final class SandboxChunks extends InputStream {
    private final InputStream input;
    private int remaining;
    private boolean delimiter;
    private boolean ended;

    SandboxChunks(InputStream input) {
        this.input = input;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
    }

    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, target.length);
        if (length == 0) {
            return 0;
        }
        if (ended) {
            return -1;
        }
        if (remaining == 0) {
            nextChunk();
            if (ended) {
                return -1;
            }
        }
        int read = input.read(target, offset, Math.min(remaining, length));
        if (read <= 0) {
            throw new IOException("Native event chunk was truncated");
        }
        remaining -= read;
        return read;
    }

    private void nextChunk() throws IOException {
        if (delimiter) {
            crlf();
        }
        int size = 0;
        int digits = 0;
        while (true) {
            int next = input.read();
            if (next == '\r') {
                if (input.read() != '\n' || digits == 0) {
                    throw new IOException("Malformed native event chunk size");
                }
                break;
            }
            int digit = next < 0 ? -1 : Character.digit((char) next, 16);
            if (digit < 0 || ++digits > 8 || size > 131072 / 16) {
                throw new IOException("Native event chunk exceeds its framing bound");
            }
            size = size * 16 + digit;
        }
        if (size > 131072) {
            throw new IOException("Native event chunk exceeds its framing bound");
        }
        delimiter = true;
        remaining = size;
        if (size == 0) {
            crlf();
            ended = true;
        }
    }

    private void crlf() throws IOException {
        if (input.read() != '\r' || input.read() != '\n') {
            throw new IOException("Malformed native event chunk delimiter or unsupported trailers");
        }
    }
}
