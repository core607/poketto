package io.github.core607.poketto.community.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Remembers which clients already read an article today. Only salted digests are kept, in memory; the
 * salt and every digest are replaced when the UTC day changes, so no stored value links back to a
 * client. When the day's capacity is used up, later readers that day are not counted.
 */
final class ReaderDigests {
    private final int capacity;
    private final Supplier<byte[]> salts;
    private final Set<Long> seen = new HashSet<>();
    private LocalDate day;
    private byte[] salt;

    ReaderDigests(int capacity, Supplier<byte[]> salts) {
        if (capacity < 1) {
            throw new IllegalArgumentException("reader capacity must be positive");
        }
        this.capacity = capacity;
        this.salts = salts;
    }

    /** True the first time these parts are seen on this day, false for repeats and beyond capacity. */
    synchronized boolean first(LocalDate today, String... parts) {
        if (!today.equals(day)) {
            day = today;
            salt = salts.get();
            seen.clear();
        }
        if (seen.size() >= capacity) {
            return false;
        }
        return seen.add(digest(parts));
    }

    private long digest(String... parts) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is unavailable", missing);
        }
        sha.update(salt);
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            // Length prefixes keep ("ab", "c") and ("a", "bc") apart.
            sha.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            sha.update(bytes);
        }
        return ByteBuffer.wrap(sha.digest()).getLong();
    }
}
