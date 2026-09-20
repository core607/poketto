package io.github.core607.poketto.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;

class GoogleTokenClientTests {
    @Test
    void exchangesFormAndParsesBoundedSuccessButRejectsOversizedSuccessAndErrors() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var payload = new AtomicReference<>(
                "{\"access_token\":\"fixture\",\"token_type\":\"Bearer\",\"id_token\":\"jwt-fixture\"}");
        var submitted = new AtomicReference<String>();
        server.createContext("/token", exchange -> {
            submitted.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = payload.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(payload.get().contains("invalid_grant") ? 400 : 200, bytes.length);
            try (exchange) {
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            var client = GoogleTokenClient.create(Duration.ofSeconds(2));
            var grant = grant(server);
            assertThat(client.getTokenResponse(grant).getAdditionalParameters())
                    .containsEntry("id_token", "jwt-fixture");
            assertThat(submitted.get())
                    .contains("code=fixture-code", "code_verifier=fixture-verifier", "grant_type=authorization_code");
            for (String prefix :
                    new String[] {"{\"access_token\":\"", "{\"error\":\"invalid_grant\",\"error_description\":\""}) {
                payload.set(prefix + "x".repeat(65_536) + "\"}");
                assertThatThrownBy(() -> client.getTokenResponse(grant))
                        .isInstanceOf(OAuth2AuthorizationException.class)
                        .hasRootCauseMessage("Google token response exceeds the byte limit");
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aStalledProviderCannotKeepTheExchangeWaitingWithoutAReadTimeout() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new CountDownLatch(1);
        server.createContext("/token", exchange -> {
            try (exchange) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        server.start();
        try {
            var client = GoogleTokenClient.create(Duration.ofMillis(150));
            assertThatThrownBy(() -> client.getTokenResponse(grant(server)))
                    .isInstanceOf(OAuth2AuthorizationException.class)
                    .hasRootCauseInstanceOf(SocketTimeoutException.class);
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    private static OAuth2AuthorizationCodeGrantRequest grant(HttpServer server) {
        var client = CommonOAuth2Provider.GOOGLE
                .getBuilder("google")
                .clientId("fixture-client")
                .clientSecret("fixture-secret")
                .redirectUri("https://site.example/callback")
                .tokenUri("http://127.0.0.1:" + server.getAddress().getPort() + "/token")
                .build();
        var request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(client.getProviderDetails().getAuthorizationUri())
                .clientId(client.getClientId())
                .redirectUri(client.getRedirectUri())
                .attributes(values -> values.put("code_verifier", "fixture-verifier"))
                .state("fixture-state")
                .build();
        var response = OAuth2AuthorizationResponse.success("fixture-code")
                .redirectUri(client.getRedirectUri())
                .state("fixture-state")
                .build();
        return new OAuth2AuthorizationCodeGrantRequest(client, new OAuth2AuthorizationExchange(request, response));
    }
}
