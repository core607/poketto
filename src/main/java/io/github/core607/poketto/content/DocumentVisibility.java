package io.github.core607.poketto.content;

import java.util.Objects;

public enum DocumentVisibility {
    PRIVATE("private"),
    PUBLIC("public");

    private final String value;

    DocumentVisibility(String value) {
        this.value = value;
    }

    public static DocumentVisibility parse(String candidate) {
        Objects.requireNonNull(candidate, "document visibility must not be null");
        return switch (candidate) {
            case "private" -> PRIVATE;
            case "public" -> PUBLIC;
            default -> throw new IllegalArgumentException(
                    "document visibility must be exactly private or public: " + candidate);
        };
    }

    @Override
    public String toString() {
        return value;
    }
}
