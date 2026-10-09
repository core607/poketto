package io.github.core607.poketto.qa.internal;

import com.anthropic.backends.AnthropicBackend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientImpl;
import com.anthropic.core.ClientOptions;
import com.anthropic.core.ObjectMappers;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import io.github.core607.poketto.qa.QaException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

/** Provider protocol and signed thinking replay belong to Spring AI and the official SDK. */
final class AnthropicQaModel implements QaModel {
    private final AnthropicChatModel chat;
    private final QaTransport transport;
    private final int outputLimit;

    AnthropicQaModel(HttpClient http, URI baseUrl, String key, String model, QaPolicy policy, ObjectMapper json) {
        outputLimit = policy.outputTokens();
        transport = new QaTransport(http, this::inspect);
        String base = baseUrl.toString();
        var backend = AnthropicBackend.builder()
                .baseUrl(base)
                .apiKey(key.isBlank() ? "unconfigured" : key)
                .build();
        var httpClient = new QaAnthropicTransport(transport, backend);
        var clientOptions =
                ClientOptions.builder().baseUrl(base).httpClient(httpClient).maxRetries(0);
        backend.applyCredentials(httpClient, clientOptions);
        AnthropicClient client = new AnthropicClientImpl(clientOptions.build());
        var options = AnthropicChatOptions.builder()
                .model(model)
                .maxTokens(outputLimit)
                .thinkingAdaptive(ThinkingConfigAdaptive.Display.SUMMARIZED)
                .cacheOptions(AnthropicCacheOptions.builder()
                        .strategy(AnthropicCacheStrategy.CONVERSATION_HISTORY)
                        .cacheToolResults(true)
                        .build())
                .toolCallbacks(QaToolSchemas.callbacks(json))
                .build();
        chat = AnthropicChatModel.builder()
                .anthropicClient(client)
                .anthropicClientAsync(client.async())
                .options(options)
                .build();
    }

    @Override
    public Completion complete(List<Message> messages, Duration remaining, Runnable beforeDispatch) {
        try {
            return transport.call(
                    remaining,
                    beforeDispatch,
                    () -> completion(chat.call(new Prompt(QaSpringMessages.convert(messages)))));
        } catch (EmptyResponse empty) {
            return empty.completion;
        } catch (AnthropicException | RestClientException | IllegalArgumentException | ArithmeticException malformed) {
            throw new QaException(
                    "UPSTREAM_UNCERTAIN", "The Anthropic response was incomplete or malformed", malformed);
        }
    }

    private Completion completion(ChatResponse response) {
        Generation result = response.getResults().getLast();
        AssistantMessage assistant = result.getOutput();
        String reason = result.getMetadata().getFinishReason();
        var usage = response.getMetadata().getUsage();
        long read = usage.getCacheReadInputTokens() == null ? 0 : usage.getCacheReadInputTokens();
        long write = usage.getCacheWriteInputTokens() == null ? 0 : usage.getCacheWriteInputTokens();
        long input = Math.addExact(usage.getPromptTokens(), Math.addExact(read, write));
        requireUsage(input, usage.getCompletionTokens());
        String thinking = response.getResults().stream()
                .map(Generation::getOutput)
                .filter(message -> message.getMetadata().containsKey("signature"))
                .map(AssistantMessage::getText)
                .reduce("", String::concat);
        return new Completion(
                QaSpringMessages.calls(assistant),
                input,
                usage.getCompletionTokens(),
                write,
                read,
                assistant.getText(),
                thinking,
                assistant,
                stop(reason));
    }

    private void inspect(byte[] bytes) {
        try {
            var message = ObjectMappers.jsonMapper().readValue(bytes, com.anthropic.models.messages.Message.class);
            message.validate();
            for (var block : message.content()) {
                if (!(block.isText() || block.isThinking() || block.isRedactedThinking() || block.isToolUse())) {
                    throw new IllegalArgumentException("Unsupported Anthropic content block");
                }
            }
            if (message.content().isEmpty()) {
                // Spring AI 2.0.1 drops usage/stop_reason and logs the prompt on this branch.
                String reason = message.stopReason().map(Object::toString).orElse("");
                var usage = message.usage();
                long read = usage.cacheReadInputTokens().orElse(0L);
                long write = usage.cacheCreationInputTokens().orElse(0L);
                long input = Math.addExact(usage.inputTokens(), Math.addExact(read, write));
                requireUsage(input, usage.outputTokens());
                throw new EmptyResponse(new Completion(
                        List.of(), input, usage.outputTokens(), write, read, "", "", null, stop(reason)));
            }
        } catch (IOException malformed) {
            throw new QaException("UPSTREAM_UNCERTAIN", "The Anthropic response was malformed", malformed);
        }
    }

    private static Stop stop(String reason) {
        return switch (reason) {
            case "end_turn", "tool_use" -> Stop.COMPLETE;
            case "refusal" -> Stop.REFUSED;
            default -> Stop.INCOMPLETE;
        };
    }

    private void requireUsage(long input, long output) {
        if (input > QaPolicy.INPUT_TOKEN_BOUND || output > outputLimit) {
            throw new IllegalArgumentException("Anthropic usage exceeds the reserved bounds");
        }
    }

    private static final class EmptyResponse extends RuntimeException {
        private final Completion completion;

        private EmptyResponse(Completion completion) {
            super("Empty Anthropic response with known usage");
            this.completion = completion;
        }
    }
}
