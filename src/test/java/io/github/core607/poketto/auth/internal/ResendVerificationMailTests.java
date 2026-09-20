package io.github.core607.poketto.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.core607.poketto.auth.EmailChallengeException;
import io.github.core607.poketto.auth.EmailPurpose;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ResendVerificationMailTests {
    @Test
    void transientRetryKeepsTheSameMessageIdAndExactBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> keys = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        server.createContext("/emails", exchange -> {
            keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fixture-secret");
            exchange.sendResponseHeaders(keys.size() == 1 ? 500 : 200, -1);
            exchange.close();
        });
        server.start();
        UUID id = UUID.randomUUID();
        try (var mail = client(server)) {
            mail.send("reader@example.test", "041203", EmailPurpose.SIGNUP, id);
            assertThat(keys).containsExactly("email-verification/" + id, "email-verification/" + id);
            assertThat(bodies).hasSize(2);
            assertThat(bodies.get(0)).isEqualTo(bodies.get(1));
            var body = JsonMapper.builder().build().readTree(bodies.getFirst());
            assertThat(body.path("from").asString()).isEqualTo("Poketto <noreply@example.test>");
            assertThat(body.path("to").get(0).asString()).isEqualTo("reader@example.test");
            assertThat(body.path("text").asString()).contains("041203", "10 分钟");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsRedirectsAndAuthenticationErrorsWithoutSendingToAnotherDestination() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        var status = new AtomicInteger(302);
        server.createContext("/emails", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", "/stolen");
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.createContext("/stolen", exchange -> {
            requests.addAndGet(100);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try (var mail = client(server)) {
            assertUnavailable(() -> mail.send("reader@example.test", "123456", EmailPurpose.BIND, UUID.randomUUID()));
            assertThat(requests.get()).isOne();
            status.set(401);
            assertUnavailable(
                    () -> mail.send("reader@example.test", "123456", EmailPurpose.RECOVERY, UUID.randomUUID()));
            assertThat(requests.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oversizedProviderResponseCannotBeReportedAsAccepted() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = new byte[16 * 1024 + 1];
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });
        server.start();
        try (var mail = client(server)) {
            assertUnavailable(() -> mail.send("reader@example.test", "123456", EmailPurpose.SIGNUP, UUID.randomUUID()));
        } finally {
            server.stop(0);
        }
    }

    private static ResendVerificationMail client(HttpServer server) {
        return new ResendVerificationMail(
                "fixture-secret",
                "Poketto <noreply@example.test>",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/emails"),
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    private static void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        EmailChallengeException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(EmailChallengeException.Code.DELIVERY_UNAVAILABLE));
    }
}
