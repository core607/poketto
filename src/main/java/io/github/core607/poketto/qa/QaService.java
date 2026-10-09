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

    record Question(UUID requestId, String question) {
        public Question {
            if (requestId == null) {
                throw new IllegalArgumentException("Question request ID is required");
            }
            text(question, 4000, "Question");
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
            Usage usage) {
        public Reply {
            paragraphs = List.copyOf(paragraphs);
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

    record Usage(int calls, long inputTokens, long outputTokens, String costUpperUsd, boolean uncertain) {}

    record Allowance(int remaining, int dailyLimit, String resetsAt, String runCostUpperUsd) {}

    static void text(String value, int maximumBytes, String name) {
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new IllegalArgumentException(name + " is empty or exceeds its UTF-8 byte limit");
        }
    }
}
