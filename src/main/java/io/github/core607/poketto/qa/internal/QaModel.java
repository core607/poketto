package io.github.core607.poketto.qa.internal;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

interface QaModel {
    default void validate(List<Message> messages) {}

    Completion complete(List<Message> messages, Duration remaining);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Message(
            String role,
            String content,
            @JsonProperty("tool_calls") List<Call> calls,
            @JsonProperty("tool_call_id") String callId,
            @JsonProperty("reasoning_content") String reasoning,
            @JsonIgnore List<JsonNode> providerContent) {
        static Message text(String role, String text) {
            return new Message(role, text, null, null, null, null);
        }

        static Message tool(String id, String text) {
            return new Message("tool", text, null, id, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Call(String id, String type, Function function) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Function(String name, String arguments) {}

    record Completion(
            List<Call> calls,
            long inputTokens,
            long outputTokens,
            long cacheCreationTokens,
            String content,
            String reasoning,
            List<JsonNode> providerContent) {
        public Completion {
            if (calls == null || calls.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Missing tool call list");
            }
            calls = List.copyOf(calls);
            if (calls.size() > 8) {
                throw new IllegalArgumentException("Expected at most eight tool calls");
            }
            var seen = new HashSet<String>();
            for (Call call : calls) {
                if (call.id() == null || !call.id().matches("[A-Za-z0-9_-]{1,128}") || !seen.add(call.id())) {
                    throw new IllegalArgumentException("Invalid tool call identity");
                }
                if (!"function".equals(call.type())
                        || call.function() == null
                        || call.function().name() == null
                        || call.function().arguments() == null) {
                    throw new IllegalArgumentException("Invalid tool function");
                }
            }
            if (inputTokens < 1 || cacheCreationTokens < 0 || cacheCreationTokens > inputTokens || outputTokens < 0) {
                throw new IllegalArgumentException("Invalid completion usage");
            }
            content = content == null ? "" : content;
            reasoning = reasoning == null ? "" : reasoning;
            providerContent = providerContent == null ? null : List.copyOf(providerContent);
        }

        Message assistant() {
            return new Message("assistant", content, calls.isEmpty() ? null : calls, null, reasoning, providerContent);
        }
    }
}
