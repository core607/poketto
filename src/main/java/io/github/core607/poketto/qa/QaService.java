package io.github.core607.poketto.qa;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** A connection selects the wishing entrance; null is the browser account entrance. */
public interface QaService {
    boolean available();

    Reply ask(AuthPrincipal actor, WorkspaceId connection, Question input);

    Reply resume(AuthPrincipal actor, WorkspaceId connection, Choice input);

    Reply status(AuthPrincipal actor, WorkspaceId connection, UUID requestId);

    Allowance allowance(AuthPrincipal actor);

    record Question(UUID requestId, String question, String provider) {
        public Question {
            if (requestId == null) {
                throw new IllegalArgumentException("Question request ID is required");
            }
            text(question, 4000, "Question");
            if (provider != null && !provider.matches("[a-z]{1,32}")) {
                throw new IllegalArgumentException("Invalid QA provider selection");
            }
        }
    }

    record Choice(UUID requestId, int revision, String answer) {
        public Choice {
            if (requestId == null || revision < 1) {
                throw new IllegalArgumentException("Continuation identity is required");
            }
            text(answer, 2000, "Clarification answer");
        }
    }

    record Reply(
            UUID requestId,
            String status,
            String code,
            int revision,
            List<Paragraph> paragraphs,
            Clarification clarification,
            String notice,
            Usage usage,
            Selection selection,
            List<Activity> activity) {
        public Reply {
            paragraphs = List.copyOf(paragraphs);
            activity = List.copyOf(activity);
        }
    }

    record Paragraph(String text, List<Citation> citations) {
        public Paragraph {
            citations = List.copyOf(citations);
        }
    }

    record Citation(String reference, String title, String url, String quote) {}

    record Clarification(String question, List<String> options, String expiresAt) {
        public Clarification {
            options = List.copyOf(options);
        }
    }

    record Usage(
            int calls,
            long inputTokens,
            long cacheReadTokens,
            long cacheWriteTokens,
            long outputTokens,
            String costUsd,
            boolean uncertain) {}

    record Selection(String requestedProvider, String provider, String model, String fallbackReason) {}

    record Activity(int id, String kind, String name, String state, String input, String output, long elapsedMillis) {}

    record ModelOption(String provider, String model, boolean configured, String runCostUpperUsd) {}

    record MonthBudget(String limitUsd, String spentUsd, String reservedUsd, String remainingUsd, String resetsAt) {}

    record Allowance(
            int remaining,
            int dailyLimit,
            String resetsAt,
            String defaultProvider,
            List<ModelOption> models,
            MonthBudget anthropicBudget) {
        public Allowance {
            models = List.copyOf(models);
        }
    }

    static void text(String value, int maximumBytes, String name) {
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new IllegalArgumentException(name + " is empty or exceeds its UTF-8 byte limit");
        }
    }
}
