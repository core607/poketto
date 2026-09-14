package io.github.core607.poketto.executor.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** The small header has its own checksum so expiry collection need not decompress archived files. */
record RetainedBaselineHeader(RetainedBaseline.Identity identity, long indexOffset, int entries, int bytes) {
    static final int INDEX_BYTES = 56;
    private static final byte[] MAGIC = "PKBL0001".getBytes(StandardCharsets.US_ASCII);
    private static final int PREFIX = 24;
    private static final int MAX_IDENTITY = 4096;

    static int size(RetainedBaseline.Identity identity, JsonMapper json) {
        return PREFIX + json.writeValueAsBytes(identity).length + 32;
    }

    static void write(
            FileChannel channel, RetainedBaseline.Identity identity, long indexOffset, int entries, JsonMapper json)
            throws IOException {
        byte[] encoded = json.writeValueAsBytes(identity);
        if (encoded.length > MAX_IDENTITY) {
            throw new RetainedBaselineIo.Limit();
        }
        var header = ByteBuffer.allocate(PREFIX + encoded.length);
        header.put(MAGIC)
                .putLong(indexOffset)
                .putInt(entries)
                .putInt(encoded.length)
                .put(encoded);
        byte[] digest = RetainedBaselineIo.sha256().digest(header.array());
        channel.position(0);
        RetainedBaselineIo.writeFully(channel, header.flip());
        RetainedBaselineIo.writeFully(channel, ByteBuffer.wrap(digest));
    }

    static RetainedBaselineHeader read(FileChannel channel, long maximum, JsonMapper json) throws IOException {
        long length = channel.size();
        if (length < PREFIX + 32 || length > maximum) {
            throw new IOException("retained baseline length is outside its bounds");
        }
        channel.position(0);
        var prefix = ByteBuffer.allocate(PREFIX);
        RetainedBaselineIo.readFully(channel, prefix);
        prefix.flip();
        byte[] magic = new byte[MAGIC.length];
        prefix.get(magic);
        long offset = prefix.getLong();
        int entries = prefix.getInt();
        int identityBytes = prefix.getInt();
        validate(length, magic, offset, entries, identityBytes);
        var tail = ByteBuffer.allocate(identityBytes + 32);
        RetainedBaselineIo.readFully(channel, tail);
        MessageDigest digest = RetainedBaselineIo.sha256();
        digest.update(prefix.array());
        digest.update(tail.array(), 0, identityBytes);
        if (!MessageDigest.isEqual(digest.digest(), Arrays.copyOfRange(tail.array(), identityBytes, tail.capacity()))) {
            throw new IOException("retained baseline header checksum differs");
        }
        try {
            var identity = json.readValue(Arrays.copyOf(tail.array(), identityBytes), RetainedBaseline.Identity.class);
            if (identity == null) {
                throw new IOException("retained baseline identity is missing");
            }
            return new RetainedBaselineHeader(identity, offset, entries, PREFIX + identityBytes + 32);
        } catch (JacksonException failure) {
            throw new IOException("retained baseline identity is invalid", failure);
        }
    }

    private static void validate(long length, byte[] magic, long offset, int entries, int identityBytes)
            throws IOException {
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("retained baseline format differs");
        }
        if (identityBytes < 1 || identityBytes > MAX_IDENTITY) {
            throw new IOException("retained baseline identity length is invalid");
        }
        if (entries < 0 || entries > 100_000) {
            throw new IOException("retained baseline entry count is invalid");
        }
        if (offset < PREFIX + identityBytes + 32 || offset > length) {
            throw new IOException("retained baseline index offset is invalid");
        }
        if ((long) entries * INDEX_BYTES != length - offset) {
            throw new IOException("retained baseline index length differs");
        }
    }
}
