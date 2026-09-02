package io.github.core607.poketto.content;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable document identity within a workspace repository.
 */
public record DocumentId(UUID value) {

    public DocumentId {
        Objects.requireNonNull(value, "document id must not be null");
    }

    public static DocumentId random() {
        return new DocumentId(UUID.randomUUID());
    }

    public static DocumentId parse(String candidate) {
        Objects.requireNonNull(candidate, "document id must not be null");

        final UUID parsed;
        try {
            parsed = UUID.fromString(candidate);
        } catch (IllegalArgumentException exception) {
            throw invalid(candidate, exception);
        }
        if (!parsed.toString().equals(candidate)) {
            throw invalid(candidate, null);
        }
        return new DocumentId(parsed);
    }

    private static IllegalArgumentException invalid(String candidate, Exception cause) {
        return new IllegalArgumentException("document id must be a canonical lowercase UUID: " + candidate, cause);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
