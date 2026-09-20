package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.core607.poketto.content.GitHubConnectionException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class GitHubAppHttpTests {
    @Test
    void sendsApiCredentialsAndOauthFormsWithoutFollowingRedirects() throws Exception {
        try (var fixture = new Fixture();
                var client = fixture.client(Duration.ofSeconds(3))) {
            var api = client.postApi("/data", "fixture-token", "{}".getBytes(StandardCharsets.UTF_8));
            assertThat(api.status()).isEqualTo(200);
            assertThat(fixture.authorization.get()).isEqualTo("Bearer fixture-token");
            assertThat(fixture.apiVersion.get()).isEqualTo("2026-03-10");
            assertThat(fixture.method.get()).isEqualTo("POST");
            assertThat(fixture.contentType.get()).isEqualTo("application/json");
            byte[] form = "client_secret=synthetic%2Bsecret".getBytes(StandardCharsets.UTF_8);
            var oauth = client.exchangeOAuth(form);
            assertThat(oauth.status()).isEqualTo(200);
            assertThat(fixture.authorization.get()).isNull();
            assertThat(fixture.contentType.get()).isEqualTo("application/x-www-form-urlencoded");
            assertThat(fixture.body.get()).isEqualTo(form);
            assertThat(client.getApi("/redirect", "fixture-token").status()).isEqualTo(302);
            assertThat(fixture.redirectHits.get()).isZero();
            assertThat(api.toString()).doesNotContain("fixture-token");
        }
    }

    @Test
    void boundsSuccessAndErrorBodiesByActualBytes() throws Exception {
        try (var fixture = new Fixture();
                var client = fixture.client(Duration.ofSeconds(3))) {
            fixture.payload.set(new byte[GitHubAppHttp.MAX_RESPONSE_BYTES]);
            assertThat(client.getApi("/data", "fixture-token").body()).hasSize(GitHubAppHttp.MAX_RESPONSE_BYTES);
            fixture.payload.set(new byte[GitHubAppHttp.MAX_RESPONSE_BYTES + 1]);
            for (int status : new int[] {200, 503}) {
                fixture.status.set(status);
                assertThatThrownBy(() -> client.getApi("/data", "fixture-token"))
                        .isInstanceOf(IOException.class)
                        .hasRootCauseMessage("Provider response exceeds its byte limit");
            }
            fixture.status.set(200);
            fixture.payload.set(
                    "\u732b".repeat(GitHubAppHttp.MAX_RESPONSE_BYTES / 3 + 1).getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> client.getApi("/data", "fixture-token"))
                    .isInstanceOf(IOException.class)
                    .hasRootCauseMessage("Provider response exceeds its byte limit");
        }
    }

    @Test
    void rejectsEscapedOriginsMalformedCredentialsAndOversizedRequestsBeforeSending() throws Exception {
        try (var fixture = new Fixture();
                var client = fixture.client(Duration.ofSeconds(3))) {
            for (String path : List.of("//example.com/", "https://example.com/", "/data#fragment")) {
                assertThatThrownBy(() -> client.getApi(path, "fixture-token"))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> client.getApi("/data", "secret\r\nInjected: value"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("secret");
            assertThatThrownBy(() -> client.postApi("/data", "fixture-token", new byte[16 * 1024 + 1]))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(fixture.hits.get()).isZero();
        }
    }

    @Test
    void refusesPrivateDnsAnswersBeforeOpeningAnExchange() throws Exception {
        try (var fixture = new Fixture();
                var client = new GitHubAppHttp(fixture.origin, fixture.origin, Duration.ofSeconds(3), host -> {
                    throw new IOException("Provider address is not public");
                })) {
            assertThatThrownBy(() -> client.getApi("/data", "fixture-token"))
                    .isInstanceOf(IOException.class)
                    .hasMessage("Provider address is not public");
            assertThat(fixture.hits.get()).isZero();
        }
    }

    @Test
    @Timeout(10)
    void deadlineIncludesAStalledBodyAfterResponseHeaders() throws Exception {
        try (var fixture = new Fixture();
                var client = fixture.client(Duration.ofSeconds(1))) {
            client.getApi("/data", "fixture-token");
            assertThatThrownBy(() -> client.getApi("/stall", "fixture-token")).isInstanceOf(IOException.class);
            assertThat(fixture.headersSent.await(1, TimeUnit.SECONDS)).isTrue();
            fixture.release.countDown();
            assertThat(client.getApi("/data", "fixture-token").status()).isEqualTo(200);
        }
    }

    @Test
    @Timeout(10)
    void closeCancelsAnOutstandingExchangeAndRejectsLaterCalls() throws Exception {
        try (var fixture = new Fixture();
                var client = fixture.client(Duration.ofSeconds(5));
                var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = callers.submit(() -> client.getApi("/stall", "fixture-token"));
            assertThat(fixture.headersSent.await(3, TimeUnit.SECONDS)).isTrue();
            client.close();
            assertThatThrownBy(() -> pending.get(3, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IOException.class);
            assertThatThrownBy(() -> client.getApi("/data", "fixture-token"))
                    .isInstanceOf(IOException.class)
                    .hasMessage("GitHub HTTP client is closed");
            fixture.release.countDown();
        }
    }

    @Test
    @Timeout(15)
    void boundsConcurrentRequestsAndReleasesCapacityAfterCompletion() throws Exception {
        try (var fixture = new Fixture();
                var client = fixture.client(Duration.ofSeconds(5));
                var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = new ArrayList<Future<GitHubAppHttp.Reply>>();
            for (int i = 0; i < 4; i++) {
                pending.add(callers.submit(() -> client.getApi("/stall", "fixture-token")));
            }
            assertThat(fixture.allStalled.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> client.getApi("/data", "fixture-token"))
                    .isInstanceOf(GitHubConnectionException.class)
                    .hasMessage("GitHub App: BUSY");
            fixture.release.countDown();
            for (Future<GitHubAppHttp.Reply> request : pending) {
                assertThat(request.get(3, TimeUnit.SECONDS).status()).isEqualTo(200);
            }
            assertThat(client.getApi("/data", "fixture-token").status()).isEqualTo(200);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final URI origin;
        private final AtomicReference<byte[]> payload = new AtomicReference<>("{}".getBytes(StandardCharsets.UTF_8));
        private final AtomicInteger status = new AtomicInteger(200);
        private final AtomicInteger hits = new AtomicInteger();
        private final AtomicInteger redirectHits = new AtomicInteger();
        private final AtomicReference<String> authorization = new AtomicReference<>();
        private final AtomicReference<String> contentType = new AtomicReference<>();
        private final AtomicReference<String> apiVersion = new AtomicReference<>();
        private final AtomicReference<String> method = new AtomicReference<>();
        private final AtomicReference<byte[]> body = new AtomicReference<>();
        private final CountDownLatch headersSent = new CountDownLatch(1);
        private final CountDownLatch allStalled = new CountDownLatch(4);
        private final CountDownLatch release = new CountDownLatch(1);

        Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            server.setExecutor(workers);
            server.createContext("/data", this::answer);
            server.createContext("/login/oauth/access_token", this::answer);
            server.createContext("/redirect", exchange -> {
                try (exchange) {
                    exchange.getResponseHeaders()
                            .add("Location", origin.resolve("/unexpected").toString());
                    exchange.sendResponseHeaders(302, -1);
                }
            });
            server.createContext("/unexpected", exchange -> {
                redirectHits.incrementAndGet();
                exchange.close();
            });
            server.createContext("/stall", this::stall);
            server.start();
        }

        GitHubAppHttp client(Duration timeout) {
            return new GitHubAppHttp(origin, origin, timeout, host -> {});
        }

        private void answer(HttpExchange exchange) throws IOException {
            hits.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            apiVersion.set(exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version"));
            method.set(exchange.getRequestMethod());
            body.set(exchange.getRequestBody().readAllBytes());
            try (exchange) {
                byte[] response = payload.get();
                exchange.sendResponseHeaders(status.get(), response.length);
                exchange.getResponseBody().write(response);
            }
        }

        private void stall(HttpExchange exchange) throws IOException {
            try (exchange) {
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write('x');
                exchange.getResponseBody().flush();
                headersSent.countDown();
                allStalled.countDown();
                try {
                    release.await(8, TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Fixture interrupted", failure);
                }
                exchange.getResponseBody().write('y');
            }
        }

        @Override
        public void close() {
            release.countDown();
            server.stop(0);
            workers.shutdownNow();
        }
    }
}
