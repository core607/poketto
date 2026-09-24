package io.github.core607.poketto.community.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Remembers, for the current UTC day, which clients already read an article and how many reports each
 * client address made. Only salted digests are kept, in memory; the salt and every digest are replaced
 * when the day changes, so no stored value links back to a client. When the day's capacity is used up,
 * later readers that day are not counted.
 */
final class ReaderDigests {
    private final int capacity;
    private final int perClient;
    private final Supplier<byte[]> salts;
    private final Set<Long> seen = new HashSet<>();
    private final Map<Long, Integer> reports = new HashMap<>();
    private LocalDate day;
    private byte[] salt;

    ReaderDigests(int capacity, int perClient, Supplier<byte[]> salts) {
        if (capacity < 1 || perClient < 1) {
            throw new IllegalArgumentException("reader capacity and per-client reports must be positive");
        }
        this.capacity = capacity;
        this.perClient = perClient;
        this.salts = salts;
    }

    /**
     * Admits one report from a client address, false once the address made {@code perClient} reports
     * today or the day's address capacity is used up. Checked before any snapshot read or write.
     */
    synchronized boolean admit(LocalDate today, String client) {
        rotate(today);
        long key = digest(client);
        Integer used = reports.get(key);
        if (used == null && reports.size() >= capacity) {
            return false;
        }
        int next = used == null ? 1 : used + 1;
        reports.put(key, next);
        return next <= perClient;
    }

    /** True the first time these parts are seen on this day, false for repeats and beyond capacity. */
    synchronized boolean first(LocalDate today, String... parts) {
        rotate(today);
        if (seen.size() >= capacity) {
            return false;
        }
        return seen.add(digest(parts));
    }

    /** Forgets a read whose count could not be stored, so the reader's next report counts. */
    synchronized void forget(LocalDate today, String... parts) {
        if (today.equals(day)) {
            seen.remove(digest(parts));
        }
    }

    private void rotate(LocalDate today) {
        if (!today.equals(day)) {
            day = today;
            salt = salts.get();
            seen.clear();
            reports.clear();
        }
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
