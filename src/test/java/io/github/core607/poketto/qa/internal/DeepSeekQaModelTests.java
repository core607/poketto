package io.github.core607.poketto.qa.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.core607.poketto.qa.QaException;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DeepSeekQaModelTests {
    private HttpServer server;
    private HttpClient client;
    private DeepSeekQaModel model;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> received = new AtomicReference<>();
    private volatile Response response = new Response(200, valid());

    @BeforeEach
    void setup() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/chat/completions", this::respond);
        server.start();
        client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        var policy = new QaPolicy(
                5, 2_000_000, 2, 6, 2048, new BigDecimal("0.3"), new BigDecimal("1.2"), Duration.ofSeconds(90), "");
        model = new DeepSeekQaModel(
                client,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions"),
                "synthetic-provider-key",
                "deepseek-flash",
                policy,
                JsonMapper.shared());
    }

    @AfterEach
    void close() {
        client.shutdownNow();
        server.stop(0);
    }

    @Test
    void sendsTheNonThinkingToolProtocolAndReadsUsage() {
        QaModel.Completion completion = model.complete(messages(), Duration.ofSeconds(5));
        assertThat(completion.calls()).hasSize(2);
        assertThat(completion.inputTokens()).isEqualTo(100);
        assertThat(completion.outputTokens()).isEqualTo(20);
        String body = received.get();
        assertThat(body)
                .contains(
                        "\"model\":\"deepseek-flash\"",
                        "\"thinking\":{\"type\":\"disabled\"}",
                        "\"tool_choice\":\"required\"",
                        "\"max_tokens\":2048");
        assertThat(body).doesNotContain("synthetic-provider-key");
        assertThat(requests).hasValue(1);
    }

    @Test
    void ambiguousErrorsMalformedUsageAndRedirectsNeverRetryAPost() {
        for (Response failure : List.of(
                new Response(503, "provider unavailable"),
                new Response(302, ""),
                new Response(200, valid().replace("\"completion_tokens\":20", "\"other\":20")),
                new Response(200, valid().replace("\"prompt_tokens\":100", "\"prompt_tokens\":999999")),
                new Response(200, "{"))) {
            response = failure;
            int before = requests.get();
            assertThatThrownBy(() -> model.complete(messages(), Duration.ofSeconds(5)))
                    .isInstanceOf(QaException.class);
            assertThat(requests).hasValue(before + 1);
        }
    }

    @Test
    void completeResponseAndRequestBytesAreBounded() {
        response = new Response(200, " ".repeat(131_073));
        assertThatThrownBy(() -> model.complete(messages(), Duration.ofSeconds(5)))
                .isInstanceOf(QaException.class);
        int before = requests.get();
        assertThatThrownBy(() -> model.validate(List.of(QaModel.Message.text("user", "字".repeat(30_000)))))
                .isInstanceOf(QaException.class)
                .hasMessageContaining("input");
        assertThat(requests).hasValue(before);
    }

    @Test
    void theDeadlineCoversABodyThatStopsArrivingAfterItsHeaders() throws IOException {
        var release = new CountDownLatch(1);
        server.removeContext("/chat/completions");
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        long started = System.nanoTime();
        try {
            assertThatThrownBy(() -> model.complete(messages(), Duration.ofMillis(150)))
                    .isInstanceOf(QaException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        } finally {
            release.countDown();
        }
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        if (response.status() == 302) {
            exchange.getResponseHeaders().set("Location", "/chat/completions");
        }
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        try {
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    private static List<QaModel.Message> messages() {
        return List.of(QaModel.Message.text("user", "What do the papers say?"));
    }

    private static String valid() {
        return """
                {"choices":[{"finish_reason":"tool_calls","message":{"tool_calls":[
                {"id":"call_1","type":"function","function":{"name":"search","arguments":"{}"}},
                {"id":"call_2","type":"function","function":{"name":"read","arguments":"{}"}}
                ]}}],"usage":{"prompt_tokens":100,"completion_tokens":20}}
                """;
    }

    private record Response(int status, String body) {}
}
