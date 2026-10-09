package io.github.core607.poketto.qa.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.List;

interface QaModel {
    default void validate(List<Message> messages) {}

    Completion complete(List<Message> messages, Duration remaining);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Message(
            String role,
            String content,
            @JsonProperty("tool_calls") List<Call> calls,
            @JsonProperty("tool_call_id") String callId) {
        static Message text(String role, String text) {
            return new Message(role, text, null, null);
        }

        static Message tool(String id, String text) {
            return new Message("tool", text, null, id);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Call(String id, String type, Function function) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Function(String name, String arguments) {}

    record Completion(List<Call> calls, long inputTokens, long outputTokens) {
        public Completion {
            calls = List.copyOf(calls);
        }
    }
}
