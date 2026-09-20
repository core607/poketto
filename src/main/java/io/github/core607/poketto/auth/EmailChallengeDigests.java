package io.github.core607.poketto.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Domain-separated HMAC key derived from the mail credential; rotation expires outstanding proofs. */
final class EmailChallengeDigests {
    private final SecretKeySpec key;

    EmailChallengeDigests(String credential) {
        try {
            byte[] material = MessageDigest.getInstance("SHA-256")
                    .digest(("poketto:email-challenge:v1\0" + credential).getBytes(StandardCharsets.UTF_8));
            key = new SecretKeySpec(material, "HmacSHA256");
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Email challenge cryptography is unavailable", exception);
        }
    }

    String of(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Email challenge digest failed", exception);
        }
    }

    boolean matches(String value, String digest) {
        return MessageDigest.isEqual(
                of(value).getBytes(StandardCharsets.US_ASCII), digest.getBytes(StandardCharsets.US_ASCII));
    }
}
