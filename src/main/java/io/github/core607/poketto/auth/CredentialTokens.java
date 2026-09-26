package io.github.core607.poketto.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Random bearer values, the digests stored in their place, and PKCE S256 challenges. */
public final class CredentialTokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private CredentialTokens() {}

    /** The prefix followed by 32 random bytes in unpadded base64url. */
    public static String random(String prefix) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return prefix + BASE64URL.encodeToString(bytes);
    }

    /** RFC 7636 S256: unpadded base64url of the SHA-256 of the verifier's ASCII bytes. */
    public static String challenge(String verifier) {
        return BASE64URL.encodeToString(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    /** Lowercase hex SHA-256 of the credential's UTF-8 bytes, stored instead of the credential. */
    static String digest(String credential) {
        return HexFormat.of().formatHex(sha256(credential.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required for credentials", impossible);
        }
    }
}
