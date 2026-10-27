package io.github.core607.poketto.executor.internal;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A worker snapshot as the worker writes it: a ustar archive whose first entry is
 * {@code manifest.json} and whose further entries are {@code files/0}, {@code files/1} and so on
 * in manifest order. Paths, sizes and digests come from the manifest; this reader checks that
 * each entry has the name and size the caller expects and reads entries strictly in order.
 */
final class SnapshotArchive {
    private static final int BLOCK = 512;
    private final InputStream input;
    private int next;
    private Entry open;

    SnapshotArchive(InputStream input) {
        this.input = input;
    }

    JsonNode manifest(ObjectMapper json) throws IOException {
        if (next != 0 || open != null) {
            throw new IOException("snapshot manifest already read");
        }
        long size = header("manifest.json");
        if (size < 2 || size > WorkerClient.MAX_FRAME) {
            throw new IOException("invalid snapshot manifest size");
        }
        byte[] bytes = readFully((int) size);
        skipPadding(size);
        return json.readTree(bytes);
    }

    /** The next file of exactly {@code bytes} bytes; the previous one must have been read to its end. */
    InputStream file(long bytes) throws IOException {
        if (open != null && open.remaining > 0) {
            throw new IOException("previous snapshot file not consumed");
        }
        if (open != null) {
            skipPadding(open.size);
        }
        long size = header("files/" + next++);
        if (size != bytes) {
            throw new IOException("snapshot file size differs from its manifest");
        }
        open = new Entry(size);
        return open;
    }

    private long header(String name) throws IOException {
        byte[] block = readFully(BLOCK);
        int end = 0;
        while (end < 100 && block[end] != 0) {
            end++;
        }
        if (!new String(block, 0, end, StandardCharsets.US_ASCII).equals(name)
                || block[156] != '0'
                || !Arrays.equals(Arrays.copyOfRange(block, 257, 263), "ustar\0".getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("unexpected snapshot entry");
        }
        long recorded = octal(block, 148, 6);
        Arrays.fill(block, 148, 156, (byte) ' ');
        long sum = 0;
        for (byte value : block) {
            sum += value & 255;
        }
        if (sum != recorded) {
            throw new IOException("snapshot header checksum mismatch");
        }
        return octal(block, 124, 11);
    }

    private static long octal(byte[] block, int offset, int digits) throws IOException {
        long value = 0;
        for (int index = offset; index < offset + digits; index++) {
            int digit = block[index] - '0';
            if (digit < 0 || digit > 7) {
                throw new IOException("invalid snapshot header number");
            }
            value = value * 8 + digit;
        }
        return value;
    }

    private void skipPadding(long size) throws IOException {
        readFully((int) ((BLOCK - size % BLOCK) % BLOCK));
    }

    private byte[] readFully(int count) throws IOException {
        byte[] bytes = input.readNBytes(count);
        if (bytes.length != count) {
            throw new EOFException("truncated snapshot");
        }
        return bytes;
    }

    private final class Entry extends InputStream {
        private final long size;
        private long remaining;

        Entry(long size) {
            this.size = size;
            this.remaining = size;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int read = input.read(bytes, offset, (int) Math.min(length, remaining));
            if (read < 0) {
                throw new EOFException("truncated snapshot file");
            }
            remaining -= read;
            return read;
        }
    }
}
