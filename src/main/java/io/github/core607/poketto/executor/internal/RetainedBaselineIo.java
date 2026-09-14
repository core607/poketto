package io.github.core607.poketto.executor.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Bounded frame streams leave their owner's file channel open for index and digest operations. */
final class RetainedBaselineIo {
    private RetainedBaselineIo() {}

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    static void readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 1) {
                throw new IOException("retained baseline is truncated");
            }
        }
    }

    static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    static String digest(FileChannel channel, long length) throws IOException {
        channel.position(0);
        MessageDigest digest = sha256();
        var buffer = ByteBuffer.allocate(64 * 1024);
        long remaining = length;
        while (remaining > 0) {
            buffer.limit((int) Math.min(buffer.capacity(), remaining));
            int read = channel.read(buffer);
            if (read < 1) {
                throw new IOException("retained baseline is truncated during verification");
            }
            remaining -= read;
            buffer.flip();
            digest.update(buffer);
            buffer.clear();
        }
        if (channel.size() != length) {
            throw new IOException("retained baseline length changed during verification");
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static final class Limit extends IOException {
        Limit() {
            super("retained baseline capacity exceeded");
        }
    }

    static final class Output extends OutputStream {
        private final OutputStream target;
        private final long limit;
        private long count;

        Output(OutputStream target, long limit) {
            this.target = target;
            this.limit = limit;
        }

        long count() {
            return count;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length > limit - count) {
                throw new Limit();
            }
            target.write(bytes, offset, length);
            count += length;
        }

        @Override
        public void close() throws IOException {
            target.flush();
        }
    }

    static final class FrameInput extends InputStream {
        private final FileChannel channel;
        private long remaining;

        FrameInput(FileChannel channel, long length) {
            this.channel = channel;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : Byte.toUnsignedInt(one[0]);
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            if (remaining == 0) {
                return -1;
            }
            int read = channel.read(ByteBuffer.wrap(bytes, offset, (int) Math.min(length, remaining)));
            if (read < 1) {
                throw new IOException("retained baseline frame is truncated");
            }
            remaining -= read;
            return read;
        }
    }
}
