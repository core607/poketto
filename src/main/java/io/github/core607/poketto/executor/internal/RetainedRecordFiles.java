package io.github.core607.poketto.executor.internal;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Set;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Streams a bounded, checksummed frame; callers publish it only after this file has been fsynced. */
final class RetainedRecordFiles {
    static final int OVERHEAD = 48;
    private static final byte[] MAGIC = "PKRT0001".getBytes(StandardCharsets.US_ASCII);
    private final JsonMapper json;
    private final long maximum;

    RetainedRecordFiles(long maximum) {
        this.maximum = maximum;
        var factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxDocumentLength(maximum)
                        .maxStringLength((int) maximum)
                        .maxNestingDepth(64)
                        .maxTokenCount(2_000_000)
                        .build())
                .build();
        json = JsonMapper.builder(factory)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    void write(Path temporary, RetainedCopyRecord record, long available) throws IOException {
        long limit = Math.min(maximum, available) - OVERHEAD;
        if (limit < 1) {
            throw new SizeLimit();
        }
        try (FileChannel file = FileChannel.open(
                temporary,
                Set.of(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            writeFully(file, ByteBuffer.allocate(16).put(MAGIC).putLong(0).flip());
            var output = new BoundedDigestOutput(Channels.newOutputStream(file), limit);
            json.writeValue(output, record);
            writeFully(file, ByteBuffer.wrap(output.digest.digest()));
            file.position(8);
            writeFully(file, ByteBuffer.allocate(8).putLong(output.count).flip());
            file.force(true);
        }
    }

    RetainedCopyRecord read(Path path) throws IOException {
        try (FileChannel file = FileChannel.open(path, READ, NOFOLLOW_LINKS)) {
            long size = file.size();
            if (size < OVERHEAD || size > maximum) {
                throw new IOException("retained record frame size is invalid");
            }
            var header = ByteBuffer.allocate(16);
            readFully(file, header);
            byte[] magic = new byte[8];
            header.flip().get(magic);
            long length = header.getLong();
            if (!Arrays.equals(magic, MAGIC) || length != size - OVERHEAD) {
                throw new IOException("retained record frame header is invalid");
            }
            verifyDigest(file, length);
            file.position(16);
            return json.readValue(new LimitedInput(Channels.newInputStream(file), length), RetainedCopyRecord.class);
        }
    }

    private static void verifyDigest(FileChannel file, long length) throws IOException {
        MessageDigest digest = sha256();
        var buffer = ByteBuffer.allocate(65536);
        long remaining = length;
        while (remaining > 0) {
            buffer.clear().limit((int) Math.min(buffer.capacity(), remaining));
            int count = file.read(buffer);
            if (count < 1) {
                throw new IOException("retained record payload is truncated");
            }
            digest.update(buffer.array(), 0, count);
            remaining -= count;
        }
        var expected = ByteBuffer.allocate(32);
        readFully(file, expected);
        if (!MessageDigest.isEqual(digest.digest(), expected.array())) {
            throw new IOException("retained record checksum mismatch");
        }
    }

    private static void readFully(FileChannel file, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (file.read(buffer) < 1) {
                throw new IOException("retained record is truncated");
            }
        }
    }

    private static void writeFully(FileChannel file, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            file.write(buffer);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static final class SizeLimit extends IOException {
        SizeLimit() {
            super("retained record byte limit exceeded");
        }
    }

    private static final class BoundedDigestOutput extends OutputStream {
        private final OutputStream target;
        private final long limit;
        private final MessageDigest digest = sha256();
        private long count;

        private BoundedDigestOutput(OutputStream target, long limit) {
            this.target = target;
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length > limit - count) {
                throw new SizeLimit();
            }
            target.write(bytes, offset, length);
            digest.update(bytes, offset, length);
            count += length;
        }

        @Override
        public void close() throws IOException {
            target.flush();
        }
    }

    private static final class LimitedInput extends FilterInputStream {
        private long remaining;

        private LimitedInput(InputStream input, long remaining) {
            super(input);
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int value = super.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (remaining == 0) {
                return -1;
            }
            int count = in.read(bytes, offset, (int) Math.min(length, remaining));
            if (count > 0) {
                remaining -= count;
            }
            return count;
        }
    }
}
