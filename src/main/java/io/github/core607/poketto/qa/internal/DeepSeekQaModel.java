package io.github.core607.poketto.qa.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.core607.poketto.qa.QaException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Bounded thinking/tool turns. No redirects, request replay, wire logging or automatic retry. */
final class DeepSeekQaModel implements QaModel {
    private final HttpClient http;
    private final URI endpoint;
    private final String key;
    private final String model;
    private final QaPolicy policy;
    private final ObjectMapper json;
    private final JsonNode tools;

    DeepSeekQaModel(HttpClient http, URI endpoint, String key, String model, QaPolicy policy, ObjectMapper json) {
        this.http = http;
        this.endpoint = endpoint;
        this.key = key;
        this.model = model;
        this.policy = policy;
        this.json = json;
        tools = QaToolSchemas.load(json);
    }

    @Override
    public void validate(List<Message> messages) {
        body(messages);
    }

    private byte[] body(List<Message> messages) {
        byte[] body = json.writeValueAsBytes(
                new Request(model, messages, tools, new Thinking("enabled"), policy.outputTokens(), false));
        if (body.length > QaPolicy.INPUT_BYTES) {
            throw new QaException("INPUT_LIMIT", "The bounded model input is full");
        }
        return body;
    }

    @Override
    public Completion complete(List<Message> messages, Duration remaining) {
        byte[] body = body(messages);
        Duration timeout = QaHttp.timeout(remaining);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return parse(QaHttp.send(http, request, timeout));
    }

    private Completion parse(byte[] bytes) {
        try {
            Response response = json.readValue(bytes, Response.class);
            if (response.usage() == null
                    || response.choices() == null
                    || response.choices().size() != 1) {
                throw new IllegalArgumentException("Missing completion or usage");
            }
            Usage usage = response.usage();
            if (usage.input() == null || usage.output() == null) {
                throw new IllegalArgumentException("Missing token usage");
            }
            if (usage.input() < 1
                    || usage.input() > QaPolicy.INPUT_TOKEN_BOUND
                    || usage.output() < 0
                    || usage.output() > policy.outputTokens()) {
                throw new IllegalArgumentException("Upstream usage exceeds the reserved bounds");
            }
            Choice choice = response.choices().getFirst();
            if (choice == null) {
                throw new IllegalArgumentException("Missing completion choice");
            }
            if (!("tool_calls".equals(choice.finish()) || "stop".equals(choice.finish())) || choice.message() == null) {
                throw new IllegalArgumentException("Expected a complete tool turn");
            }
            Assistant assistant = choice.message();
            List<Call> calls = assistant.calls() == null ? List.of() : assistant.calls();
            return new Completion(
                    calls, usage.input(), usage.output(), 0, assistant.content(), assistant.reasoning(), null, false);
        } catch (JacksonException | IllegalArgumentException malformed) {
            throw new QaException("UPSTREAM_UNCERTAIN", "The upstream response was incomplete or malformed", malformed);
        }
    }

    private record Thinking(String type) {}

    private record Request(
            String model,
            List<Message> messages,
            JsonNode tools,
            Thinking thinking,
            @JsonProperty("max_tokens") int maximum,
            boolean stream) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Response(List<Choice> choices, Usage usage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Choice(@JsonProperty("finish_reason") String finish, Assistant message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Assistant(
            @JsonProperty("tool_calls") List<Call> calls,
            String content,
            @JsonProperty("reasoning_content") String reasoning) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Usage(
            @JsonProperty("prompt_tokens") Long input,
            @JsonProperty("completion_tokens") Long output) {}
}
