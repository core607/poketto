package io.github.core607.poketto.auth.internal;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.CredentialTokens;
import io.github.core607.poketto.auth.EmailChallengeException;
import io.github.core607.poketto.auth.GoogleAccounts;
import io.github.core607.poketto.auth.GoogleIdentityProvider;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** Standard token exchange and OIDC validation with explicit session state, PKCE, and nonce binding. */
final class GoogleOidcProvider implements GoogleIdentityProvider {
    static final String CALLBACK = "/api/auth/identity/google/callback";
    private final ClientRegistration client;
    private final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokens;
    private final JwtDecoder decoder;

    GoogleOidcProvider(String id, String secret, String origin) {
        client = id.isBlank() || secret.isBlank() || origin.isBlank() ? null : registration(id, secret, origin);
        tokens = GoogleTokenClient.create(Duration.ofSeconds(10));
        decoder = client == null ? null : new OidcIdTokenDecoderFactory().createDecoder(client);
    }

    GoogleOidcProvider(
            ClientRegistration client,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokens,
            JwtDecoder decoder) {
        this.client = client;
        this.tokens = tokens;
        this.decoder = decoder;
    }

    @Override
    public boolean available() {
        return client != null;
    }

    @Override
    public Authorization begin() {
        requireAvailable();
        String state = CredentialTokens.random("");
        String nonce = CredentialTokens.random("");
        String verifier = CredentialTokens.random("");
        return new Authorization(request(state, nonce, verifier).getAuthorizationRequestUri(), state, nonce, verifier);
    }

    @Override
    public GoogleAccounts.Identity exchange(Authorization authorization, String code) {
        requireAvailable();
        if (code == null || code.isBlank() || code.length() > 2048) {
            throw new AuthException(AuthException.Code.INVALID_CREDENTIALS);
        }
        var response = OAuth2AuthorizationResponse.success(code)
                .state(authorization.state())
                .redirectUri(client.getRedirectUri())
                .build();
        var exchange = new OAuth2AuthorizationExchange(
                request(authorization.state(), authorization.nonce(), authorization.verifier()), response);
        try {
            var token = tokens.getTokenResponse(new OAuth2AuthorizationCodeGrantRequest(client, exchange));
            Object raw = token.getAdditionalParameters().get("id_token");
            if (!(raw instanceof String encoded) || encoded.length() > 16384) {
                throw new AuthException(AuthException.Code.INVALID_CREDENTIALS);
            }
            Jwt verified = decoder.decode(encoded);
            requireNonce(verified, authorization.nonce());
            if (!Boolean.TRUE.equals(verified.getClaimAsBoolean("email_verified"))) {
                throw new AuthException(AuthException.Code.INVALID_CREDENTIALS);
            }
            return new GoogleAccounts.Identity(
                    verified.getSubject(), verified.getClaimAsString("email"), verified.getClaimAsString("name"));
        } catch (OAuth2AuthorizationException
                | JwtException
                | EmailChallengeException
                | IllegalArgumentException failure) {
            // Provider responses and tokens must not enter the public response or application logs.
            throw new AuthException(AuthException.Code.INVALID_CREDENTIALS, failure);
        }
    }

    private OAuth2AuthorizationRequest request(String state, String nonce, String verifier) {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(client.getProviderDetails().getAuthorizationUri())
                .clientId(client.getClientId())
                .redirectUri(client.getRedirectUri())
                .scopes(client.getScopes())
                .state(state)
                .attributes(values -> values.put("code_verifier", verifier))
                .additionalParameters(values -> {
                    values.put("nonce", nonce);
                    values.put("code_challenge", CredentialTokens.challenge(verifier));
                    values.put("code_challenge_method", "S256");
                    values.put("prompt", "select_account");
                })
                .build();
    }

    private static void requireNonce(Jwt token, String expected) {
        String actual = token.getClaimAsString("nonce");
        if (actual == null
                || !MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            throw new AuthException(AuthException.Code.INVALID_CREDENTIALS);
        }
    }

    private void requireAvailable() {
        if (!available()) {
            throw new AuthException(AuthException.Code.DENIED);
        }
    }

    private static ClientRegistration registration(String id, String secret, String origin) {
        URI uri = URI.create(origin);
        boolean loopback = "http".equals(uri.getScheme())
                && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
        if ((!"https".equals(uri.getScheme()) && !loopback)
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || !uri.getRawPath().isEmpty()
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "Google login requires an exact HTTPS origin, or HTTP loopback for local testing");
        }
        return CommonOAuth2Provider.GOOGLE
                .getBuilder("google")
                .clientId(id)
                .clientSecret(secret)
                .redirectUri(origin + CALLBACK)
                .scope("openid", "email", "profile")
                .build();
    }
}
