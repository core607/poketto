package io.github.core607.poketto.auth;

import java.util.UUID;

/** Only creation returns the token. Callers must prevent response, URL, and audit logging. */
public final class IssuedToken {
    private final UUID id;
    private final String token;

    IssuedToken(UUID id, String token) {
        this.id = id;
        this.token = token;
    }

    public UUID id() {
        return id;
    }

    public String token() {
        return token;
    }

    @Override
    public String toString() {
        return "IssuedToken[id=" + id + ", token=REDACTED]";
    }
}
