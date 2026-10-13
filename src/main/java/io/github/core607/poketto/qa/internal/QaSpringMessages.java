package io.github.core607.poketto.qa.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/** Keeps the framework's original assistant message, including opaque provider thinking state. */
final class QaSpringMessages {
    private QaSpringMessages() {}

    static List<Message> convert(List<QaModel.Message> history) {
        var result = new ArrayList<Message>();
        var names = new HashMap<String, String>();
        for (QaModel.Message message : history) {
            if (message.calls() != null) {
                message.calls()
                        .forEach(call -> names.put(call.id(), call.function().name()));
            }
            switch (message.role()) {
                case "system" -> result.add(new SystemMessage(message.content()));
                case "user" -> result.add(new UserMessage(message.content()));
                case "assistant" ->
                    result.add(message.providerContent() == null ? assistant(message) : message.providerContent());
                case "tool" -> {
                    var responses = new ArrayList<ToolResponseMessage.ToolResponse>();
                    if (!result.isEmpty() && result.getLast() instanceof ToolResponseMessage previous) {
                        responses.addAll(previous.getResponses());
                        result.removeLast();
                    }
                    responses.add(new ToolResponseMessage.ToolResponse(
                            message.callId(), names.getOrDefault(message.callId(), "unknown"), message.content()));
                    result.add(
                            ToolResponseMessage.builder().responses(responses).build());
                }
                default -> throw new IllegalArgumentException("Unsupported QA message role");
            }
        }
        return List.copyOf(result);
    }

    private static AssistantMessage assistant(QaModel.Message message) {
        List<AssistantMessage.ToolCall> calls = message.calls() == null
                ? List.of()
                : message.calls().stream()
                        .map(call -> new AssistantMessage.ToolCall(
                                call.id(),
                                call.type(),
                                call.function().name(),
                                call.function().arguments()))
                        .toList();
        return AssistantMessage.builder()
                .content(message.content())
                .toolCalls(calls)
                .build();
    }

    static List<QaModel.Call> calls(AssistantMessage message) {
        return message.getToolCalls().stream()
                .map(call ->
                        new QaModel.Call(call.id(), call.type(), new QaModel.Function(call.name(), call.arguments())))
                .toList();
    }
}
