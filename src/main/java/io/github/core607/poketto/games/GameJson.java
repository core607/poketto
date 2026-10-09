package io.github.core607.poketto.games;

import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A save is untrusted JSON, bounded identically at browser, application and worker entrances. */
public final class GameJson {
    private GameJson() {}

    public static void require(JsonNode value, int maximum, ObjectMapper json) {
        Objects.requireNonNull(value, "Game JSON is required");
        try {
            tree(value, 0);
        } catch (IllegalArgumentException invalid) {
            throw new GameException("INVALID_GAME", invalid.getMessage(), invalid);
        }
        if (json.writeValueAsBytes(value).length > maximum) {
            throw new GameException("GAME_LIMIT", "Game JSON exceeds its byte limit");
        }
    }

    private static void tree(JsonNode node, int depth) {
        if (depth > 16 || node.size() > 2048) {
            throw new GameException("GAME_LIMIT", "Game JSON exceeds its structure limit");
        }
        if (node.isNumber() && !Double.isFinite(node.doubleValue())) {
            throw new GameException("INVALID_GAME", "Game JSON numbers must be finite");
        }
        if (node.isString()) {
            GameBundle.text(node.stringValue(), 32 * 1024, "Game JSON string");
        }
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                GameBundle.text(name, 256, "Game JSON field name");
            }
        }
        for (JsonNode child : node) {
            tree(child, depth + 1);
        }
    }
}
