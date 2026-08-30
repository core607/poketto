package io.github.core607.poketto.content;

import java.util.Objects;

/**
 * Kind of caller a content write is attributed to.
 */
public enum PrincipalType {
    ACCOUNT("account"),
    API_KEY("api-key"),
    SYSTEM("system");

    private final String value;

    PrincipalType(String value) {
        this.value = value;
    }

    public static PrincipalType parse(String candidate) {
        Objects.requireNonNull(candidate, "principal type must not be null");
        for (PrincipalType type : values()) {
            if (type.value.equals(candidate)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown principal type: " + candidate);
    }

    @Override
    public String toString() {
        return value;
    }
}
