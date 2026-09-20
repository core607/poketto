package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitHubAppOAuthTests {
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final URI CALLBACK = URI.create("https://example.test/api/github/callback");
    private static final String RESPONSE = """
            {"access_token":"ghu_fixtureAccess","expires_in":28800,
             "refresh_token":"ghr_fixtureRefresh","refresh_token_expires_in":15897600,
             "token_type":"bearer","scope":""}
            """;

    @Test
    void authorizationAndExchangeUseMatchingStatePkceAndExactCallback() throws Exception {
        try (var fixture = new GitHubAppFixture()) {
            GitHubAppOAuth oauth = oauth(fixture);
            GitHubAppOAuth.Flow flow = oauth.begin();
            GitHubAppOAuth.Flow another = oauth.begin();
            assertThat(flow.state()).isNotEqualTo(another.state()).hasSize(43);
            assertThat(flow.verifier()).isNotEqualTo(another.verifier()).hasSize(43);
            URI authorization = oauth.authorization(flow);
            assertThat(authorization.getHost()).isEqualTo("github.com");
            assertThat(authorization.getPath()).isEqualTo("/login/oauth/authorize");
            Map<String, String> query = form(authorization.getRawQuery());
            assertThat(query)
                    .containsEntry("state", flow.state())
                    .containsEntry("code_challenge_method", "S256")
                    .containsEntry("prompt", "select_account")
                    .containsEntry("redirect_uri", CALLBACK.toString());
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(flow.verifier().getBytes(StandardCharsets.US_ASCII));
            assertThat(query.get("code_challenge"))
                    .isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
            assertThat(query).doesNotContainKeys("scope", "client_secret", "code_verifier");
            fixture.reply(200, RESPONSE);
            GitHubAppOAuth.Tokens tokens = oauth.exchange(flow, flow.state(), "fixture-code");
            assertThat(tokens.accessExpiresAt()).isEqualTo(NOW.plusSeconds(28800));
            assertThat(tokens.refreshExpiresAt()).isEqualTo(NOW.plusSeconds(15897600));
            GitHubAppFixture.Request request = fixture.requests.getFirst();
            assertThat(request.path()).isEqualTo("/login/oauth/access_token");
            assertThat(request.authorization()).isNull();
            assertThat(form(request.body()))
                    .hasSize(5)
                    .containsEntry("code", "fixture-code")
                    .containsEntry("code_verifier", flow.verifier())
                    .containsEntry("client_secret", "fixture-secret")
                    .containsEntry("redirect_uri", CALLBACK.toString());
            assertThat(flow.toString()).doesNotContain(flow.state(), flow.verifier());
            assertThat(tokens.toString()).doesNotContain(tokens.accessToken(), tokens.refreshToken());
            assertThat(oauth.toString()).doesNotContain("fixture-secret");
        }
    }

    @Test
    void rejectsForeignExpiredAndFutureFlowsBeforeContactingProvider() throws Exception {
        try (var fixture = new GitHubAppFixture()) {
            GitHubAppOAuth oauth = oauth(fixture);
            GitHubAppOAuth.Flow flow = oauth.begin();
            assertThatThrownBy(() -> oauth.exchange(flow, oauth.begin().state(), "code"))
                    .hasMessage("GitHub App: AUTHORIZATION_REQUIRED");
            var expired = new GitHubAppOAuth.Flow(flow.state(), flow.verifier(), NOW.minusSeconds(600));
            assertThatThrownBy(() -> oauth.exchange(expired, expired.state(), "code"))
                    .hasMessage("GitHub App: AUTHORIZATION_REQUIRED");
            var future = new GitHubAppOAuth.Flow(flow.state(), flow.verifier(), NOW.plusSeconds(1));
            assertThatThrownBy(() -> oauth.exchange(future, future.state(), "code"))
                    .hasMessage("GitHub App: AUTHORIZATION_REQUIRED");
            assertThat(fixture.requests).isEmpty();
        }
    }

    @Test
    void refreshUsesRefreshGrantAndReturnsReplacementPairWithoutRetrying() throws Exception {
        try (var fixture = new GitHubAppFixture()) {
            fixture.reply(200, RESPONSE);
            GitHubAppOAuth.Tokens result = oauth(fixture).refresh("ghr_oldRefresh");
            assertThat(result.accessToken()).isEqualTo("ghu_fixtureAccess");
            assertThat(result.refreshToken()).isEqualTo("ghr_fixtureRefresh");
            assertThat(fixture.requests).hasSize(1);
            assertThat(form(fixture.requests.getFirst().body()))
                    .hasSize(4)
                    .containsEntry("grant_type", "refresh_token")
                    .containsEntry("refresh_token", "ghr_oldRefresh")
                    .doesNotContainKeys("code", "code_verifier", "redirect_uri");
        }
    }

    @Test
    void supportsExplicitProviderOptOutFromTokenExpiration() throws Exception {
        try (var fixture = new GitHubAppFixture()) {
            fixture.reply(200, "{\"access_token\":\"ghu_noExpiry\",\"token_type\":\"bearer\",\"scope\":\"\"}");
            GitHubAppOAuth oauth = oauth(fixture);
            GitHubAppOAuth.Flow flow = oauth.begin();
            GitHubAppOAuth.Tokens result = oauth.exchange(flow, flow.state(), "code");
            assertThat(result.accessExpiresAt()).isNull();
            assertThat(result.refreshToken()).isNull();
            assertThat(result.refreshExpiresAt()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "partial",
                "float",
                "string",
                "negative",
                "overflow",
                "scope",
                "token-type",
                "duplicate",
                "syntax"
            })
    void rejectsMalformedTokenResponsesWithoutCredentialDiagnostics(String variant) throws Exception {
        String response =
                switch (variant) {
                    case "partial" -> RESPONSE.replace("\"expires_in\":28800,", "");
                    case "float" -> RESPONSE.replace("28800", "28800.5");
                    case "string" -> RESPONSE.replace("28800", "\"28800\"");
                    case "negative" -> RESPONSE.replace("28800", "-1");
                    case "overflow" -> RESPONSE.replace("15897600", "9223372036854775807");
                    case "scope" -> RESPONSE.replace("\"scope\":\"\"", "\"scope\":\"repo\"");
                    case "token-type" -> RESPONSE.replace("bearer", "other");
                    case "duplicate" ->
                        RESPONSE.replace("\"access_token\":", "\"access_token\":\"first\",\"access_token\":");
                    default -> "{\"access_token\":ghu_fixtureAccess";
                };
        try (var fixture = new GitHubAppFixture()) {
            fixture.reply(200, response);
            GitHubAppOAuth oauth = oauth(fixture);
            assertThatThrownBy(() -> oauth.refresh("ghr_oldRefresh"))
                    .isInstanceOfSatisfying(GitHubAppFailure.class, failure -> {
                        assertThat(failure.code()).isEqualTo(GitHubAppFailure.Code.INVALID_RESPONSE);
                        var trace = new StringWriter();
                        failure.printStackTrace(new PrintWriter(trace));
                        assertThat(trace.toString())
                                .doesNotContain("ghu_fixtureAccess", "ghr_fixtureRefresh", "ghr_oldRefresh");
                    });
            assertThat(fixture.requests).hasSize(1);
        }
    }

    @Test
    void distinguishesUserReauthorizationFromAppConfigurationAndOutages() throws Exception {
        try (var fixture = new GitHubAppFixture()) {
            GitHubAppOAuth oauth = oauth(fixture);
            fixture.reply(200, "{\"error\":\"bad_refresh_token\",\"error_description\":\"secret\"}");
            assertThatThrownBy(() -> oauth.refresh("ghr_oldRefresh")).hasMessage("GitHub App: AUTHORIZATION_REQUIRED");
            fixture.reply(200, "{\"error\":\"incorrect_client_credentials\"}");
            assertThatThrownBy(() -> oauth.refresh("ghr_oldRefresh")).hasMessage("GitHub App: UNAVAILABLE");
            fixture.reply(503, "provider unavailable");
            assertThatThrownBy(() -> oauth.refresh("ghr_oldRefresh")).hasMessage("GitHub App: UNAVAILABLE");
            assertThat(fixture.requests).hasSize(3);
        }
    }

    private static GitHubAppOAuth oauth(GitHubAppFixture fixture) {
        return new GitHubAppOAuth(
                fixture.http, "Iv.fixture", "fixture-secret", CALLBACK, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Map<String, String> form(String value) {
        return Arrays.stream(value.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> decode(pair[0]), pair -> decode(pair[1])));
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
