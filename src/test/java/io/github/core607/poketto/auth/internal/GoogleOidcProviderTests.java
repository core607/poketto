package io.github.core607.poketto.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.GoogleIdentityProvider;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;

class GoogleOidcProviderTests {
    @Test
    void validatesRealSignaturesIssuerAudienceExpiryNonceAndVerifiedEmail() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("fixture-key").generate();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/keys", exchange -> {
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var registration = CommonOAuth2Provider.GOOGLE
                    .getBuilder("google")
                    .clientId("fixture-client")
                    .clientSecret("fixture-secret")
                    .redirectUri("https://site.example/api/auth/identity/google/callback")
                    .jwkSetUri("http://127.0.0.1:" + server.getAddress().getPort() + "/keys")
                    .build();
            var encoded = new AtomicReference<String>();
            var verifier = new AtomicReference<String>();
            var provider = new GoogleOidcProvider(
                    registration,
                    request -> {
                        verifier.set(request.getAuthorizationExchange()
                                .getAuthorizationRequest()
                                .getAttribute("code_verifier"));
                        return OAuth2AccessTokenResponse.withToken("discarded-fixture-access-token")
                                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                                .additionalParameters(Map.of("id_token", encoded.get()))
                                .build();
                    },
                    new OidcIdTokenDecoderFactory().createDecoder(registration));
            GoogleIdentityProvider.Authorization authorization = provider.begin();
            assertThat(authorization.url())
                    .contains("code_challenge_method=S256", "nonce=", "state=")
                    .doesNotContain("fixture-secret", authorization.verifier(), "offline", "gmail");
            assertThat(authorization.toString())
                    .doesNotContain(authorization.state(), authorization.nonce(), authorization.verifier());
            encoded.set(sign(key, claims(authorization.nonce()).build()));
            assertThat(provider.exchange(authorization, "fixture-code").email()).isEqualTo("reader@example.test");
            assertThat(verifier.get()).isEqualTo(authorization.verifier());
            assertRejected(
                    provider,
                    authorization,
                    encoded,
                    sign(key, claims("wrong-nonce").build()));
            assertRejected(
                    provider,
                    authorization,
                    encoded,
                    sign(
                            key,
                            claims(authorization.nonce())
                                    .claim("email_verified", false)
                                    .build()));
            assertRejected(
                    provider,
                    authorization,
                    encoded,
                    sign(
                            key,
                            claims(authorization.nonce())
                                    .audience("other-client")
                                    .build()));
            assertRejected(
                    provider,
                    authorization,
                    encoded,
                    sign(
                            key,
                            claims(authorization.nonce())
                                    .issuer("https://other.example")
                                    .build()));
            assertRejected(
                    provider,
                    authorization,
                    encoded,
                    sign(
                            key,
                            claims(authorization.nonce())
                                    .issueTime(Date.from(Instant.now().minusSeconds(1000)))
                                    .expirationTime(Date.from(Instant.now().minusSeconds(600)))
                                    .build()));
            RSAKey stranger = new RSAKeyGenerator(2048).keyID("fixture-key").generate();
            assertRejected(
                    provider,
                    authorization,
                    encoded,
                    sign(stranger, claims(authorization.nonce()).build()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingConfigurationIsUnavailableAndUnsafeCallbacksAreRejected() {
        assertThat(new GoogleOidcProvider("", "", "").available()).isFalse();
        assertThatThrownBy(() -> new GoogleOidcProvider("id", "secret", "http://site.example"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GoogleOidcProvider("id", "secret", "https://site.example/path"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GoogleOidcProvider("", "", "").begin()).isInstanceOf(AuthException.class);
    }

    private static JWTClaimsSet.Builder claims(String nonce) {
        return new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .audience("fixture-client")
                .subject("fixture-subject")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim("nonce", nonce)
                .claim("email", "reader@example.test")
                .claim("email_verified", true)
                .claim("name", "Reader");
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) throws Exception {
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static void assertRejected(
            GoogleOidcProvider provider,
            GoogleIdentityProvider.Authorization authorization,
            AtomicReference<String> encoded,
            String token) {
        encoded.set(token);
        assertThatThrownBy(() -> provider.exchange(authorization, "fixture-code"))
                .isInstanceOf(AuthException.class)
                .hasMessageNotContaining(token);
    }
}
