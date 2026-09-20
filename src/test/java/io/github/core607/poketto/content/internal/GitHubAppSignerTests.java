package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GitHubAppSignerTests {
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static KeyPair keys;
    private static String encoded;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        encoded = Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded());
    }

    @Test
    void signsGithubClaimsThatAnIndependentRsaVerifierAccepts() throws Exception {
        var signer = new GitHubAppSigner("Iv1.synthetic", encoded, Clock.fixed(NOW, ZoneOffset.UTC));
        String compact = signer.token();
        SignedJWT jwt = SignedJWT.parse(compact);
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("Iv1.synthetic");
        assertThat(jwt.getJWTClaimsSet().getIssueTime().toInstant()).isEqualTo(NOW.minusSeconds(60));
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant()).isEqualTo(NOW.plusSeconds(540));
        String[] parts = compact.split("\\.");
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keys.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
        assertThat(signer.toString()).doesNotContain(encoded, compact);
    }

    @Test
    void renewsBeforeExpiryAndAfterClockMovesBackwards() throws Exception {
        var clock = new MutableClock();
        var signer = new GitHubAppSigner("Iv1.synthetic", encoded, clock);
        String first = signer.token();
        clock.now = NOW.plusSeconds(479);
        assertThat(signer.token()).isEqualTo(first);
        clock.now = NOW.plusSeconds(480);
        String renewed = signer.token();
        assertThat(renewed).isNotEqualTo(first);
        assertThat(SignedJWT.parse(renewed)
                        .getJWTClaimsSet()
                        .getExpirationTime()
                        .toInstant())
                .isEqualTo(clock.now.plusSeconds(540));
        clock.now = NOW.minusSeconds(3600);
        assertThat(SignedJWT.parse(signer.token())
                        .getJWTClaimsSet()
                        .getIssueTime()
                        .toInstant())
                .isEqualTo(clock.now.minusSeconds(60));
    }

    @Test
    void rejectsMalformedOrWeakKeysAndInvalidIssuerWithoutPrintingCredentials() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        String weak = Base64.getEncoder()
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        for (String key : new String[] {"", "not-base64!", "A".repeat(16_385), encoded + "\n", weak}) {
            assertThatThrownBy(() -> new GitHubAppSigner("Iv1.synthetic", key, Clock.systemUTC()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new GitHubAppSigner("bad\r\nissuer", encoded, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(encoded);
        generator = KeyPairGenerator.getInstance("EC");
        String ec = Base64.getEncoder()
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        assertThatThrownBy(() -> new GitHubAppSigner("Iv1.synthetic", ec, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(ec);
    }

    private static final class MutableClock extends Clock {
        private Instant now = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
