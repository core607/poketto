package io.github.core607.poketto.content.internal;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.Objects;

/** App authentication only; repository access requires a separately scoped installation token. */
final class GitHubAppSigner {
    private final String clientId;
    private final RSAPrivateKey key;
    private final Clock clock;
    private Generated cached;

    /** Deployment supplies a single-line Base64 encoding of an unencrypted PKCS#8 RSA key. */
    GitHubAppSigner(String clientId, String encodedKey, Clock clock) {
        if (!validClientId(clientId)) {
            throw new IllegalArgumentException("GitHub App client ID must be 1-128 ASCII identifier characters");
        }
        this.clientId = clientId;
        this.key = signingKey(encodedKey);
        this.clock = Objects.requireNonNull(clock, "GitHub App signing clock is required");
    }

    synchronized String token() {
        Instant now = clock.instant();
        if (cached != null && cached.usableAt(now)) {
            return cached.value();
        }
        var claims = new JWTClaimsSet.Builder()
                .issuer(clientId)
                .issueTime(Date.from(now.minusSeconds(60)))
                .expirationTime(Date.from(now.plusSeconds(540)))
                .build();
        var token = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .build(),
                claims);
        try {
            token.sign(new RSASSASigner(key));
        } catch (JOSEException failure) {
            throw new IllegalStateException("GitHub App authentication could not be signed", failure);
        }
        cached = new Generated(token.serialize(), now, now.plusSeconds(480));
        return cached.value();
    }

    private static boolean validClientId(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,128}");
    }

    private static RSAPrivateKey signingKey(String encoded) {
        if (!validEncoding(encoded)) {
            throw new IllegalArgumentException("GitHub App signing key must be bounded single-line Base64");
        }
        byte[] bytes = Base64.getDecoder().decode(encoded);
        try {
            PrivateKey parsed = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
            if (!(parsed instanceof RSAPrivateKey rsa)) {
                throw new IllegalArgumentException("GitHub App signing key must be RSA");
            }
            if (!supportedSize(rsa)) {
                throw new IllegalArgumentException("GitHub App RSA signing key must contain 2048-8192 bits");
            }
            return rsa;
        } catch (GeneralSecurityException failure) {
            throw new IllegalArgumentException("GitHub App signing key must be unencrypted PKCS#8 RSA", failure);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static boolean validEncoding(String value) {
        return value != null && value.length() <= 16_384 && value.matches("[A-Za-z0-9+/]+={0,2}");
    }

    private static boolean supportedSize(RSAPrivateKey key) {
        int bits = key.getModulus().bitLength();
        return bits >= 2048 && bits <= 8192;
    }

    @Override
    public String toString() {
        return "GitHubAppSigner[redacted]";
    }

    private record Generated(String value, Instant generatedAt, Instant refreshAt) {
        boolean usableAt(Instant now) {
            return !now.isBefore(generatedAt) && now.isBefore(refreshAt);
        }

        @Override
        public String toString() {
            return "GitHubAppAuthentication[redacted]";
        }
    }
}
