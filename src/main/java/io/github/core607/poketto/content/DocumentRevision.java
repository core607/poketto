package io.github.core607.poketto.content;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Opaque exact-byte document revision token.
 */
public record DocumentRevision(String value) {

    private static final Pattern TOKEN = Pattern.compile("sha256:[0-9a-f]{64}");

    public DocumentRevision {
        Objects.requireNonNull(value, "document revision must not be null");
        if (!TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException("document revision must use sha256:<lowercase-hex>: " + value);
        }
    }

    public static DocumentRevision sha256(byte[] bytes) {
        Objects.requireNonNull(bytes, "document bytes must not be null");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return new DocumentRevision("sha256:" + HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
