package io.github.core607.poketto.content;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Caller a content write is attributed to, as a stable identifier that is safe to publish in
 * commit history. Content repositories may be mirrored off-host, so entrances must map credentials
 * and session tokens to durable identifiers before constructing this value. Its restricted syntax
 * prevents display names, email addresses, and commit-trailer injection; syntax alone cannot
 * determine whether an otherwise valid token is secret.
 */
public record WritePrincipal(PrincipalType type, String identifier) {

    private static final Pattern OPAQUE_IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    public static final WritePrincipal SYSTEM = new WritePrincipal(PrincipalType.SYSTEM, "poketto");

    public WritePrincipal {
        Objects.requireNonNull(type, "principal type must not be null");
        Objects.requireNonNull(identifier, "principal identifier must not be null");
        if (!OPAQUE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    "principal identifier must be an opaque token of up to 64 characters from "
                            + "[A-Za-z0-9._-] starting alphanumerically: " + identifier);
        }
    }

    /**
     * Renders the {@code Poketto-Principal} commit trailer value.
     */
    public String trailerValue() {
        return type + ":" + identifier;
    }

    @Override
    public String toString() {
        return trailerValue();
    }
}
