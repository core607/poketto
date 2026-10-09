package io.github.core607.poketto.qa.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.core607.poketto.qa.QaException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AnthropicQaModelTests {
    private final JsonMapper json = JsonMapper.shared();
    private HttpServer server;
    private HttpClient http;
    private AnthropicQaModel model;
    private volatile String response = valid();
    private volatile int status = 200;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> key = new AtomicReference<>();
    private final AtomicReference<String> version = new AtomicReference<>();

    @BeforeEach
    void setup() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", this::respond);
        server.start();
        http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        model = new AnthropicQaModel(
                http,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "synthetic-anthropic-key",
                "claude-haiku-5-5",
                new QaPolicy(5, 2_000_000, 2, 6, 8192, 20_000_000, Duration.ofSeconds(90), ""),
                json);
    }

    @AfterEach
    void close() {
        http.shutdownNow();
        server.stop(0);
    }

    @Test
    void nativeThinkingSummaryAndOpaqueSignaturesSurviveMultipleToolResults() {
        QaModel.Completion completion = model.complete(messages(), Duration.ofSeconds(5), () -> {});
        JsonNode request = received.get();
        assertThat(key).hasValue("synthetic-anthropic-key");
        assertThat(version).hasValue("2023-06-01");
        assertThat(request.at("/system/0/text").asText()).isEqualTo("Only public evidence.");
        assertThat(request.at("/thinking/type").asText()).isEqualTo("adaptive");
        assertThat(request.at("/thinking/display").asText()).isEqualTo("summarized");
        assertThat(request.at("/messages/0/content/0/cache_control/type").asText())
                .isEqualTo("ephemeral");
        assertThat(completion.cacheReadTokens()).isEqualTo(30);
        assertThat(request.at("/tools/0/input_schema").isObject()).isTrue();
        assertThat(completion.calls()).hasSize(2);
        assertThat(completion.reasoning()).isEqualTo("I will read the sources.");
        assertThat(completion.inputTokens()).isEqualTo(150);
        assertThat(completion.cacheCreationTokens()).isEqualTo(20);
        model.complete(
                List.of(
                        messages().getFirst(),
                        messages().getLast(),
                        completion.assistant(),
                        QaModel.Message.tool("toolu_a", "first result"),
                        QaModel.Message.tool("toolu_b", "second result")),
                Duration.ofSeconds(5),
                () -> {});
        JsonNode turns = received.get().path("messages");
        assertThat(received.get().at("/messages/2/content/1/cache_control/type").asText())
                .isEqualTo("ephemeral");
        assertThat(turns.size()).isEqualTo(3);
        assertThat(turns.get(1).path("content"))
                .isEqualTo(json.readTree(valid()).path("content"));
        assertThat(turns.at("/2/role").asText()).isEqualTo("user");
        assertThat(turns.at("/2/content/0/tool_use_id").asText()).isEqualTo("toolu_a");
        assertThat(turns.at("/2/content/1/tool_use_id").asText()).isEqualTo("toolu_b");
        assertThat(completion.reasoning()).doesNotContain("opaque-signature", "encrypted");
    }

    @Test
    void malformedUsageAndHttpFailuresStayUncertainWithoutRetry() {
        for (String body : List.of(
                "{",
                valid().replace("\"output_tokens\":42", "\"other\":42"),
                valid().replace("\"input_tokens\":100", "\"input_tokens\":999999"),
                valid().replace("\"cache_creation_input_tokens\":20", "\"cache_creation_input_tokens\":-1"),
                " ".repeat(131_073))) {
            response = body;
            assertUncertainOnce();
        }
        response = valid();
        status = 503;
        assertUncertainOnce();
        status = 302;
        assertUncertainOnce();
        int before = requests.get();
        assertThatThrownBy(() -> model.complete(
                        List.of(QaModel.Message.text("user", "字".repeat(30000))), Duration.ofSeconds(5), () -> {
                            throw new AssertionError("Must not dispatch oversized input");
                        }))
                .isInstanceOf(QaException.class);
        assertThat(requests).hasValue(before);
    }

    @Test
    void aCompletePlainTextTurnIsAvailableToTheBoundedToolLoop() {
        response = """
                {"id":"msg_fixture","type":"message","role":"assistant","model":"claude-haiku-5-5","stop_reason":"end_turn","content":[{"type":"text","text":"Need sources."}],
                "usage":{"input_tokens":40,"output_tokens":4}}
                """;
        QaModel.Completion result = model.complete(messages(), Duration.ofSeconds(5), () -> {});
        assertThat(result.calls()).isEmpty();
        assertThat(result.content()).isEqualTo("Need sources.");
    }

    @Test
    void authorizationFailurePreventsHttpAndPreservesItsCode() {
        assertThatThrownBy(() -> model.complete(messages(), Duration.ofSeconds(5), () -> {
                    throw new QaException("OWNER_CONSENT_REQUIRED", "Consent was revoked");
                }))
                .isInstanceOf(QaException.class)
                .satisfies(failure -> assertThat(((QaException) failure).code()).isEqualTo("OWNER_CONSENT_REQUIRED"));
        assertThat(requests).hasValue(0);
    }

    @Test
    void aTruncatedTurnStillReturnsItsKnownCacheUsageForSettlement() {
        response = valid().replace("\"stop_reason\":\"tool_use\"", "\"stop_reason\":\"max_tokens\"");
        QaModel.Completion result = model.complete(messages(), Duration.ofSeconds(5), () -> {});
        assertThat(result.stop()).isEqualTo(QaModel.Stop.INCOMPLETE);
        assertThat(result.inputTokens()).isEqualTo(150);
        assertThat(result.cacheReadTokens()).isEqualTo(30);
        assertThat(result.cacheCreationTokens()).isEqualTo(20);
        assertThat(requests).hasValue(1);
    }

    @Test
    void refusalIsAnExplicitResultWithReportedUsageRatherThanAnUncertainEmptyTurn() {
        response = """
                {"id":"msg_fixture","type":"message","role":"assistant","model":"claude-haiku-5-5","stop_reason":"refusal","content":[],
                "usage":{"input_tokens":40,"output_tokens":4}}
                """;
        int before = requests.get();
        QaModel.Completion result = model.complete(messages(), Duration.ofSeconds(5), () -> {});
        assertThat(result.stop()).isEqualTo(QaModel.Stop.REFUSED);
        assertThat(result.inputTokens()).isEqualTo(40);
        assertThat(result.outputTokens()).isEqualTo(4);
        assertThat(requests).hasValue(before + 1);
    }

    private void assertUncertainOnce() {
        int before = requests.get();
        assertThatThrownBy(() -> model.complete(messages(), Duration.ofSeconds(5), () -> {}))
                .isInstanceOf(QaException.class)
                .satisfies(failure -> assertThat(((QaException) failure).code()).isEqualTo("UPSTREAM_UNCERTAIN"));
        assertThat(requests).hasValue(before + 1);
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        received.set(json.readTree(exchange.getRequestBody().readAllBytes()));
        key.set(exchange.getRequestHeaders().getFirst("x-api-key"));
        version.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
        if (status == 302) {
            exchange.getResponseHeaders().set("Location", "/v1/messages");
        }
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try {
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    private static List<QaModel.Message> messages() {
        return List.of(
                QaModel.Message.text("system", "Only public evidence."), QaModel.Message.text("user", "Find papers."));
    }

    private static String valid() {
        return """
                {"id":"msg_fixture","type":"message","role":"assistant","model":"claude-haiku-5-5","stop_reason":"tool_use","content":[
                {"type":"thinking","thinking":"I will read the sources.","signature":"opaque-signature"},
                {"type":"redacted_thinking","data":"encrypted"},
                {"type":"tool_use","id":"toolu_a","name":"search","input":{"query":"rain","tag":"","offset":0}},
                {"type":"tool_use","id":"toolu_b","name":"search","input":{"query":"garden","tag":"","offset":0}}],
                "usage":{"input_tokens":100,"output_tokens":42,"cache_creation_input_tokens":20,"cache_read_input_tokens":30}}
                """;
    }
}
