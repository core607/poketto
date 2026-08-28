package io.github.core607.poketto.workspace;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity for a workspace. Its string form is always a canonical lowercase UUID.
 */
public record WorkspaceId(UUID value) {

    public WorkspaceId {
        Objects.requireNonNull(value, "workspace id must not be null");
    }

    public static WorkspaceId random() {
        return new WorkspaceId(UUID.randomUUID());
    }

    public static WorkspaceId parse(String candidate) {
        Objects.requireNonNull(candidate, "workspace id must not be null");

        final UUID parsed;
        try {
            parsed = UUID.fromString(candidate);
        } catch (IllegalArgumentException exception) {
            throw invalid(candidate, exception);
        }
        if (!parsed.toString().equals(candidate)) {
            throw invalid(candidate, null);
        }
        return new WorkspaceId(parsed);
    }

    private static IllegalArgumentException invalid(String candidate, Exception cause) {
        return new IllegalArgumentException(
                "workspace id must be a canonical lowercase UUID: " + candidate,
                cause);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
