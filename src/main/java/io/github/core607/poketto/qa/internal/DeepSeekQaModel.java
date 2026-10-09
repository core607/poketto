package io.github.core607.poketto.qa.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.core607.poketto.qa.QaException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Non-streaming, non-thinking tool turns. No redirects, request replay, wire logging or automatic retry. */
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
        try (var stream = DeepSeekQaModel.class.getResourceAsStream("/qa/tools.json")) {
            if (stream == null) {
                throw new IllegalStateException("QA tool schema is missing");
            }
            tools = json.readTree(stream);
        } catch (IOException failure) {
            throw new IllegalStateException("QA tool schema could not be loaded", failure);
        }
    }

    @Override
    public void validate(List<Message> messages) {
        body(messages);
    }

    private byte[] body(List<Message> messages) {
        byte[] body = json.writeValueAsBytes(new Request(
                model, messages, tools, "required", new Thinking("disabled"), policy.outputTokens(), false));
        if (body.length > QaPolicy.INPUT_BYTES) {
            throw new QaException("INPUT_LIMIT", "The bounded model input is full");
        }
        return body;
    }

    @Override
    public Completion complete(List<Message> messages, Duration remaining) {
        byte[] body = body(messages);
        Duration timeout = remaining.compareTo(Duration.ofSeconds(45)) > 0 ? Duration.ofSeconds(45) : remaining;
        if (timeout.isNegative() || timeout.isZero()) {
            throw new QaException("QA_EXPIRED", "Question time limit reached");
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request, ignored -> new BoundedModelBody());
        try {
            HttpResponse<byte[]> response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) {
                throw new QaException("UPSTREAM_UNCERTAIN", "The upstream call did not return a usable completion");
            }
            return parse(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new QaException(
                    "UPSTREAM_UNCERTAIN", "The upstream call was interrupted and will not be replayed", interrupted);
        } catch (ExecutionException | TimeoutException failed) {
            throw new QaException(
                    "UPSTREAM_UNCERTAIN", "The upstream outcome is unknown and will not be replayed", failed);
        } finally {
            pending.cancel(true);
        }
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
            if (!"tool_calls".equals(choice.finish()) || choice.message() == null) {
                throw new IllegalArgumentException("Expected a complete tool turn");
            }
            List<Call> calls = choice.message().calls();
            validateCalls(calls);
            return new Completion(calls, usage.input(), usage.output());
        } catch (JacksonException | IllegalArgumentException malformed) {
            throw new QaException("UPSTREAM_UNCERTAIN", "The upstream response was incomplete or malformed", malformed);
        }
    }

    private static void validateCalls(List<Call> calls) {
        if (calls == null || calls.isEmpty() || calls.size() > 8) {
            throw new IllegalArgumentException("Expected one to eight tool calls");
        }
        var seen = new HashSet<String>();
        for (Call call : calls) {
            if (call == null
                    || call.id() == null
                    || !call.id().matches("[A-Za-z0-9_-]{1,128}")
                    || !seen.add(call.id())) {
                throw new IllegalArgumentException("Invalid tool call identity");
            }
            if (!"function".equals(call.type())
                    || call.function() == null
                    || call.function().name() == null
                    || call.function().arguments() == null) {
                throw new IllegalArgumentException("Invalid tool function");
            }
        }
    }

    private record Thinking(String type) {}

    private record Request(
            String model,
            List<Message> messages,
            JsonNode tools,
            @JsonProperty("tool_choice") String choice,
            Thinking thinking,
            @JsonProperty("max_tokens") int maximum,
            boolean stream) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Response(List<Choice> choices, Usage usage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Choice(@JsonProperty("finish_reason") String finish, Assistant message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Assistant(@JsonProperty("tool_calls") List<Call> calls) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Usage(
            @JsonProperty("prompt_tokens") Long input,
            @JsonProperty("completion_tokens") Long output) {}
}
