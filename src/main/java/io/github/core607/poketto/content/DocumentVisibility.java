package io.github.core607.poketto.content;

import java.util.Objects;

/**
 * Whether a document is part of the public site. Its text form is the value stored in
 * frontmatter, so parsing accepts only those two spellings: an unrecognized value is
 * invalid content rather than a private default, because guessing would publish nothing
 * silently or, worse, publish something unintended.
 */
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
            default ->
                throw new IllegalArgumentException(
                        "document visibility must be exactly private or public: " + candidate);
        };
    }

    @Override
    public String toString() {
        return value;
    }
}
