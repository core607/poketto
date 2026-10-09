package io.github.core607.poketto.qa.internal;

import io.github.core607.poketto.qa.QaException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

final class DeepSeekQaModel implements QaModel {
    private final DeepSeekChatModel chat;
    private final QaTransport transport;
    private final int outputLimit;

    DeepSeekQaModel(HttpClient http, URI endpoint, String key, String model, QaPolicy policy, ObjectMapper json) {
        outputLimit = policy.outputTokens();
        transport = new QaTransport(http, bytes -> {
            DeepSeekApi.ChatCompletion response = json.readValue(bytes, DeepSeekApi.ChatCompletion.class);
            if (response == null
                    || response.choices() == null
                    || response.choices().size() != 1) {
                throw new IllegalArgumentException("Expected one complete DeepSeek choice");
            }
            requireUsage(response.usage());
            DeepSeekApi.ChatCompletion.Choice choice = response.choices().getFirst();
            if (choice == null || choice.message() == null || choice.index() == null || choice.finishReason() == null) {
                throw new IllegalArgumentException("Incomplete DeepSeek choice");
            }
        });
        var api = DeepSeekApi.builder()
                .baseUrl(endpoint.resolve("/").toString())
                .completionsPath(endpoint.getPath())
                .apiKey(key.isBlank() ? "unconfigured" : key)
                .restClientBuilder(transport.rest())
                .build();
        var options = DeepSeekChatOptions.builder()
                .model(model)
                .maxTokens(outputLimit)
                .enableThinking()
                .toolCallbacks(QaToolSchemas.callbacks(json))
                .build();
        chat = DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .options(options)
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)))
                .build();
    }

    @Override
    public Completion complete(List<Message> messages, Duration remaining, Runnable beforeDispatch) {
        try {
            return transport.call(
                    remaining,
                    beforeDispatch,
                    () -> completion(chat.call(new Prompt(QaSpringMessages.convert(messages)))));
        } catch (RestClientException | JacksonException | IllegalArgumentException malformed) {
            throw new QaException("UPSTREAM_UNCERTAIN", "The DeepSeek response was incomplete or malformed", malformed);
        }
    }

    private Completion completion(ChatResponse response) {
        var generation = response.getResult();
        if (!Set.of("STOP", "TOOL_CALLS").contains(generation.getMetadata().getFinishReason())) {
            throw new IllegalArgumentException("Expected a complete DeepSeek tool turn");
        }
        var usage = (DeepSeekApi.Usage) response.getMetadata().getUsage().getNativeUsage();
        requireUsage(usage);
        long read = usage.promptTokensDetails() == null
                        || usage.promptTokensDetails().cachedTokens() == null
                ? 0
                : usage.promptTokensDetails().cachedTokens();
        var assistant = (DeepSeekAssistantMessage) generation.getOutput();
        if (assistant.getText() == null) {
            assistant = assistant.mutate().content("").build();
        }
        return new Completion(
                QaSpringMessages.calls(assistant),
                usage.promptTokens(),
                usage.completionTokens(),
                0,
                read,
                assistant.getText(),
                assistant.getReasoningContent(),
                assistant,
                false);
    }

    private void requireUsage(DeepSeekApi.Usage usage) {
        if (usage == null || usage.promptTokens() == null || usage.completionTokens() == null) {
            throw new IllegalArgumentException("Missing DeepSeek token usage");
        }
        if (usage.promptTokens() > QaPolicy.INPUT_TOKEN_BOUND || usage.completionTokens() > outputLimit) {
            throw new IllegalArgumentException("DeepSeek usage exceeds the reserved bounds");
        }
    }
}
