package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.GitHubConnectionException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/** Strict protocol decoding; diagnostics must not retain a credential-bearing JSON source. */
final class GitHubAppJson {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    private GitHubAppJson() {}

    static <T> T read(byte[] body, Class<T> type) {
        try {
            T result = JSON.readValue(body, type);
            if (result == null) {
                throw new GitHubConnectionException(GitHubConnectionException.Code.INVALID_RESPONSE);
            }
            return result;
        } catch (JacksonException malformed) {
            // Jackson messages and their causes can quote tokens, even with source locations disabled.
            // Discard that diagnostic at this credential boundary rather than leaking it through logs.
            throw new GitHubConnectionException(GitHubConnectionException.Code.INVALID_RESPONSE);
        }
    }

    static byte[] write(Object request) {
        return JSON.writeValueAsBytes(request);
    }

    static void require(boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("GitHub response field is missing or invalid");
        }
    }
}
