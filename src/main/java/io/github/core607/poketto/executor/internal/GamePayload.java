package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.games.GameBundle;
import io.github.core607.poketto.games.GameException;
import io.github.core607.poketto.games.GameJson;
import io.github.core607.poketto.games.GameRunner;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.ObjectMapper;

/** Poketto interprets game input and output; the sandbox service only transports bytes. */
final class GamePayload {
    static final int OUTPUT_BYTES = 96 * 1024;
    private final ObjectMapper json;

    GamePayload(ObjectMapper json) {
        this.json = json;
    }

    byte[] input(GameBundle bundle, GameRunner.Request request) {
        bounded(bundle, GameBundle.MAX_BYTES);
        if (request.state() != null) {
            GameJson.require(request.state(), 32 * 1024, json);
        }
        return bounded(new Input(bundle, request), 512 * 1024);
    }

    GameRunner.Result result(GameBundle bundle, String output) {
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > OUTPUT_BYTES) {
            throw new GameException("INVALID_GAME", "The game exceeded its output bound");
        }
        GameRunner.Result result;
        try {
            result = SandcrabProtocol.read(bytes, GameRunner.Result.class);
        } catch (WorkerUnavailableException invalid) {
            // The strict native decoder discards Jackson diagnostics that could quote private state.
            throw new GameException("INVALID_GAME", "The game returned an invalid JSON step", invalid);
        }
        GameJson.require(result.state(), 32 * 1024, json);
        bounded(result.observation(), 32 * 1024);
        bounded(result.presentation(), 32 * 1024);
        if (result.presentation() != null && result.presentation().image() != null) {
            if (!bundle.resources().containsKey(result.presentation().image())) {
                throw new GameException("INVALID_GAME", "Presentation names an undeclared image");
            }
        }
        return result;
    }

    private byte[] bounded(Object value, int maximum) {
        byte[] bytes = json.writeValueAsBytes(value);
        if (bytes.length > maximum) {
            throw new GameException("GAME_LIMIT", "Game JSON exceeds its byte limit");
        }
        return bytes;
    }

    private record Input(GameBundle bundle, GameRunner.Request request) {}
}
