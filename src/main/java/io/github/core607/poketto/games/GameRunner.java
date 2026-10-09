package io.github.core607.poketto.games;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** One bounded isolated job per step. Implementations must never fall back to a host process. */
public interface GameRunner {
    Result run(Identity identity, GameBundle bundle, Request request);

    record Identity(UUID principalId, UUID accountId, WorkspaceId workspaceId) {
        public Identity {
            if (principalId == null || accountId == null || workspaceId == null) {
                throw new IllegalArgumentException("A game job requires server-selected identity");
            }
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Request(String mode, JsonNode state, String action, Long seed) {
        public Request {
            if (!List.of("init", "observe", "act").contains(mode)) {
                throw new IllegalArgumentException("Unknown game operation");
            }
            if (mode.equals("init")) {
                if (seed == null || seed < 0 || seed > 0xffffffffL) {
                    throw new IllegalArgumentException("A game initialization requires a 32-bit seed");
                }
            } else if (state == null) {
                throw new IllegalArgumentException("A game step requires state");
            }
            if (mode.equals("act")) {
                GameBundle.text(action, 128, "Game action");
            }
        }
    }

    record Result(JsonNode state, Observation observation, Presentation presentation) {
        public Result {
            if (state == null || observation == null) {
                throw new IllegalArgumentException("A game result requires state and observation");
            }
        }
    }

    record Observation(String text, List<Action> actions, Boolean done) {
        public Observation {
            GameBundle.text(text, 24 * 1024, "Observation");
            if (actions == null || actions.size() > 32) {
                throw new IllegalArgumentException("A game offers at most 32 actions");
            }
            actions = List.copyOf(actions);
            if (actions.stream().map(Action::id).distinct().count() != actions.size()) {
                throw new IllegalArgumentException("Game action IDs must be unique");
            }
            if (Boolean.TRUE.equals(done) && !actions.isEmpty()) {
                throw new IllegalArgumentException("A completed game offers no actions");
            }
        }
    }

    record Action(String id, String label) {
        public Action {
            GameBundle.text(id, 128, "Action ID");
            GameBundle.text(label, 256, "Action label");
            if (id.isEmpty() || label.isEmpty()) {
                throw new IllegalArgumentException("Game action ID and label cannot be empty");
            }
            if (id.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Game action IDs cannot contain control characters");
            }
        }
    }

    record Presentation(String heading, List<String> paragraphs, String image) {
        public Presentation {
            GameBundle.text(heading, 512, "Game heading");
            if (paragraphs == null || paragraphs.size() > 32) {
                throw new IllegalArgumentException("A game presentation allows at most 32 paragraphs");
            }
            paragraphs = List.copyOf(paragraphs);
            paragraphs.forEach(value -> GameBundle.text(value, 4096, "Game paragraph"));
            if (image != null) {
                GameBundle.text(image, 256, "Game image resource");
            }
        }
    }
}
