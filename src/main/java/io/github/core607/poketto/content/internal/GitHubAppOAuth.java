package io.github.core607.poketto.content.internal;

import static io.github.core607.poketto.content.GitHubConnectionException.Code.AUTHORIZATION_REQUIRED;
import static io.github.core607.poketto.content.GitHubConnectionException.Code.UNAVAILABLE;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.core607.poketto.content.GitHubConnectionException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/** OAuth protocol only. The callback owner binds and consumes Flow with the Poketto browser session. */
final class GitHubAppOAuth {
    private final GitHubAppHttp http;
    private final String clientId;
    private final String clientSecret;
    private final URI callback;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    GitHubAppOAuth(GitHubAppHttp http, String clientId, String clientSecret, URI callback, Clock clock) {
        if (clientId == null || !clientId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("GitHub App client ID is invalid");
        }
        validateClientSecret(clientSecret);
        if (!validCallback(callback)) {
            throw new IllegalArgumentException(
                    "GitHub App callback must be HTTPS without query, fragment or user info");
        }
        this.http = http;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.callback = callback;
        this.clock = Objects.requireNonNull(clock, "OAuth clock is required");
    }

    Flow begin() {
        return new Flow(randomValue(), randomValue(), clock.instant());
    }

    URI authorization(Flow flow) {
        return URI.create("https://github.com/login/oauth/authorize?client_id=" + encoded(clientId)
                + "&redirect_uri=" + encoded(callback.toString())
                + "&state=" + encoded(flow.state())
                + "&code_challenge=" + challenge(flow.verifier())
                + "&code_challenge_method=S256&prompt=select_account");
    }

    Tokens exchange(Flow flow, String returnedState, String code) {
        if (!validFlow(flow, returnedState)) {
            throw new GitHubConnectionException(AUTHORIZATION_REQUIRED);
        }
        if (!credential(code)) {
            throw new GitHubConnectionException(AUTHORIZATION_REQUIRED);
        }
        String form = clientForm() + "&code=" + encoded(code) + "&redirect_uri=" + encoded(callback.toString())
                + "&code_verifier=" + encoded(flow.verifier());
        return tokens(form);
    }

    /** Refresh replaces both tokens; its durable caller serializes the exchange and versioned save. */
    Tokens refresh(String refreshToken) {
        if (!credential(refreshToken)) {
            throw new GitHubConnectionException(AUTHORIZATION_REQUIRED);
        }
        return tokens(clientForm() + "&grant_type=refresh_token&refresh_token=" + encoded(refreshToken));
    }

    private Tokens tokens(String form) {
        Instant requestedAt = clock.instant();
        GitHubAppHttp.Reply reply;
        try {
            reply = http.exchangeOAuth(form.getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new GitHubConnectionException(UNAVAILABLE, failure);
        }
        if (reply.status() != 200) {
            // A provider outage or client-configuration problem must not be persisted as user revocation.
            throw new GitHubConnectionException(UNAVAILABLE);
        }
        TokenResponse response = GitHubAppJson.read(reply.body(), TokenResponse.class);
        if (response.error() != null) {
            throw oauthError(response.error());
        }
        return new Tokens(
                response.accessToken(),
                expiry(requestedAt, response.expiresIn()),
                response.refreshToken(),
                expiry(requestedAt, response.refreshExpiresIn()));
    }

    private static GitHubConnectionException oauthError(String error) {
        return switch (error) {
            case "bad_verification_code", "bad_refresh_token", "expired_token", "invalid_grant" ->
                new GitHubConnectionException(AUTHORIZATION_REQUIRED);
            default -> new GitHubConnectionException(UNAVAILABLE);
        };
    }

    private boolean validFlow(Flow flow, String returnedState) {
        if (flow == null || returnedState == null) {
            return false;
        }
        if (returnedState.length() != 43) {
            return false;
        }
        Instant now = clock.instant();
        return !now.isBefore(flow.issuedAt())
                && now.isBefore(flow.issuedAt().plusSeconds(600))
                && MessageDigest.isEqual(
                        flow.state().getBytes(StandardCharsets.UTF_8), returnedState.getBytes(StandardCharsets.UTF_8));
    }

    private String clientForm() {
        return "client_id=" + encoded(clientId) + "&client_secret=" + encoded(clientSecret);
    }

    private String randomValue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required for GitHub PKCE", failure);
        }
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Instant expiry(Instant start, Long seconds) {
        return seconds == null ? null : start.plusSeconds(seconds);
    }

    private static boolean credential(String value) {
        return value != null && value.length() <= 2048 && value.matches("[A-Za-z0-9._~+/-]+={0,2}");
    }

    static void validateClientSecret(String value) {
        if (!credential(value)) {
            throw new IllegalArgumentException("GitHub App client secret is missing or invalid");
        }
    }

    private static boolean validCallback(URI callback) {
        return callback != null
                && "https".equals(callback.getScheme())
                && callback.getHost() != null
                && callback.getUserInfo() == null
                && callback.getQuery() == null
                && callback.getFragment() == null;
    }

    @Override
    public String toString() {
        return "GitHubAppOAuth[redacted]";
    }

    record Flow(String state, String verifier, Instant issuedAt) {
        Flow {
            GitHubAppJson.require(state != null && state.matches("[A-Za-z0-9_-]{43}"));
            GitHubAppJson.require(verifier != null && verifier.matches("[A-Za-z0-9_-]{43}"));
            Objects.requireNonNull(issuedAt, "GitHub OAuth issue time is required");
        }

        @Override
        public String toString() {
            return "GitHubOAuthFlow[redacted]";
        }
    }

    record Tokens(String accessToken, Instant accessExpiresAt, String refreshToken, Instant refreshExpiresAt) {
        Tokens {
            GitHubAppJson.require(credential(accessToken));
            GitHubAppJson.require(validExpirations(accessExpiresAt, refreshToken, refreshExpiresAt));
        }

        private static boolean validExpirations(Instant access, String refresh, Instant refreshExpiry) {
            if (access == null && refresh == null && refreshExpiry == null) {
                return true;
            }
            return access != null && credential(refresh) && refreshExpiry != null && refreshExpiry.isAfter(access);
        }

        @Override
        public String toString() {
            return "GitHubUserTokens[redacted]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Long expiresIn,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("refresh_token_expires_in") Long refreshExpiresIn,
            @JsonProperty("token_type") String tokenType,
            String scope,
            String error) {
        TokenResponse {
            if (error == null) {
                GitHubAppJson.require(credential(accessToken));
                GitHubAppJson.require("bearer".equalsIgnoreCase(tokenType));
                GitHubAppJson.require("".equals(scope));
                GitHubAppJson.require(validExpiry(expiresIn, refreshToken, refreshExpiresIn));
            }
        }

        private static boolean validExpiry(Long access, String refresh, Long refreshExpiry) {
            if (access == null && refresh == null && refreshExpiry == null) {
                return true;
            }
            return access != null
                    && access > 0
                    && access <= 86_400
                    && credential(refresh)
                    && refreshExpiry != null
                    && refreshExpiry > access
                    && refreshExpiry <= 31_622_400;
        }

        @Override
        public String toString() {
            return "GitHubTokenResponse[redacted]";
        }
    }
}
