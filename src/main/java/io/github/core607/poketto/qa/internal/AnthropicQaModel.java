package io.github.core607.poketto.qa.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.core607.poketto.qa.QaException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Native Messages protocol; public thinking summaries are distinct from opaque blocks retained for replay. */
final class AnthropicQaModel implements QaModel {
    private final HttpClient http;
    private final URI endpoint;
    private final String key;
    private final String model;
    private final QaPolicy policy;
    private final ObjectMapper json;
    private final List<Tool> tools;

    AnthropicQaModel(HttpClient http, URI endpoint, String key, String model, QaPolicy policy, ObjectMapper json) {
        this.http = http;
        this.endpoint = endpoint;
        this.key = key;
        this.model = model;
        this.policy = policy;
        this.json = json;
        var definitions = new ArrayList<Tool>();
        for (JsonNode item : QaToolSchemas.load(json)) {
            ToolDefinition definition = json.treeToValue(item, ToolDefinition.class);
            ToolFunction function = definition.function();
            definitions.add(new Tool(function.name(), function.description(), function.parameters()));
        }
        tools = List.copyOf(definitions);
    }

    @Override
    public void validate(List<Message> messages) {
        body(messages);
    }

    private byte[] body(List<Message> messages) {
        String system = messages.stream()
                .filter(value -> value.role().equals("system"))
                .map(Message::content)
                .reduce("", (left, right) -> left + right + "\n");
        byte[] body = json.writeValueAsBytes(new Request(
                model,
                policy.outputTokens(),
                system,
                convert(messages),
                tools,
                new ToolChoice("auto"),
                new Thinking("adaptive", "summarized"),
                false));
        if (body.length > QaPolicy.INPUT_BYTES) {
            throw new QaException("INPUT_LIMIT", "The bounded model input is full");
        }
        return body;
    }

    private List<Turn> convert(List<Message> messages) {
        var turns = new ArrayList<Turn>();
        for (Message message : messages) {
            if (message.role().equals("system")) {
                continue;
            }
            if (message.providerContent() != null) {
                turns.add(new Turn(message.role(), message.providerContent()));
                continue;
            }
            var blocks = new ArrayList<JsonNode>();
            String role = message.role();
            if (role.equals("tool")) {
                role = "user";
                blocks.add(json.valueToTree(
                        new Block("tool_result", null, null, null, null, message.callId(), message.content())));
            } else {
                if (message.content() != null && !message.content().isEmpty()) {
                    blocks.add(json.valueToTree(new Block("text", message.content(), null, null, null, null, null)));
                }
                if (message.calls() != null) {
                    for (Call call : message.calls()) {
                        blocks.add(json.valueToTree(new Block(
                                "tool_use",
                                null,
                                call.id(),
                                call.function().name(),
                                json.readTree(call.function().arguments()),
                                null,
                                null)));
                    }
                }
            }
            if (!turns.isEmpty() && turns.getLast().role().equals(role)) {
                Turn previous = turns.removeLast();
                var combined = new ArrayList<JsonNode>(previous.content());
                combined.addAll(blocks);
                blocks = combined;
            }
            turns.add(new Turn(role, List.copyOf(blocks)));
        }
        return List.copyOf(turns);
    }

    @Override
    public Completion complete(List<Message> messages, Duration remaining) {
        byte[] payload = body(messages);
        Duration timeout = QaHttp.timeout(remaining);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("x-api-key", key)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        return parse(QaHttp.send(http, request, timeout));
    }

    private Completion parse(byte[] bytes) {
        try {
            Response response = json.readValue(bytes, Response.class);
            if (!("tool_use".equals(response.stopReason()) || "end_turn".equals(response.stopReason()))
                    || response.content() == null
                    || response.usage() == null) {
                throw new IllegalArgumentException("Expected a complete Anthropic tool turn with usage");
            }
            var calls = new ArrayList<Call>();
            var thinking = new StringBuilder();
            var content = new StringBuilder();
            for (JsonNode raw : response.content()) {
                if (raw == null || raw.isNull()) {
                    throw new IllegalArgumentException("Missing Anthropic content block");
                }
                Output block = json.treeToValue(raw, Output.class);
                if ("tool_use".equals(block.type())) {
                    if (block.input() == null || !block.input().isObject()) {
                        throw new IllegalArgumentException("Invalid Anthropic tool input");
                    }
                    calls.add(new Call(
                            block.id(),
                            "function",
                            new Function(block.name(), json.writeValueAsString(block.input()))));
                } else if ("thinking".equals(block.type())) {
                    thinking.append(block.thinking() == null ? "" : block.thinking());
                } else if ("text".equals(block.type())) {
                    content.append(block.text() == null ? "" : block.text());
                } else if (!"redacted_thinking".equals(block.type())) {
                    throw new IllegalArgumentException("Unexpected Anthropic content block");
                }
            }
            Usage usage = response.usage();
            if (usage.input() == null || usage.output() == null || usage.input() < 0 || usage.output() < 0) {
                throw new IllegalArgumentException("Missing or negative Anthropic token usage");
            }
            long created = cacheTokens(usage.created());
            long input = Math.addExact(usage.input(), Math.addExact(created, cacheTokens(usage.read())));
            if (input > QaPolicy.INPUT_TOKEN_BOUND || usage.output() > policy.outputTokens()) {
                throw new IllegalArgumentException("Anthropic usage exceeds the reserved bounds");
            }
            return new Completion(
                    calls, input, usage.output(), created, content.toString(), thinking.toString(), response.content());
        } catch (JacksonException | IllegalArgumentException | ArithmeticException malformed) {
            throw new QaException(
                    "UPSTREAM_UNCERTAIN", "The Anthropic response was incomplete or malformed", malformed);
        }
    }

    private static long cacheTokens(Long value) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException("Negative Anthropic cache token usage");
        }
        return value == null ? 0 : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ToolDefinition(ToolFunction function) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ToolFunction(String name, String description, JsonNode parameters) {}

    private record Tool(
            String name,
            String description,
            @JsonProperty("input_schema") JsonNode schema) {}

    private record ToolChoice(String type) {}

    private record Thinking(String type, String display) {}

    private record Turn(String role, List<JsonNode> content) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Block(
            String type,
            String text,
            String id,
            String name,
            JsonNode input,
            @JsonProperty("tool_use_id") String toolId,
            String content) {}

    private record Request(
            String model,
            @JsonProperty("max_tokens") int maximum,
            String system,
            List<Turn> messages,
            List<Tool> tools,
            @JsonProperty("tool_choice") ToolChoice choice,
            Thinking thinking,
            boolean stream) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Response(
            List<JsonNode> content,
            @JsonProperty("stop_reason") String stopReason,
            Usage usage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Output(String type, String id, String name, JsonNode input, String thinking, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Usage(
            @JsonProperty("input_tokens") Long input,
            @JsonProperty("output_tokens") Long output,
            @JsonProperty("cache_creation_input_tokens") Long created,
            @JsonProperty("cache_read_input_tokens") Long read) {}
}
