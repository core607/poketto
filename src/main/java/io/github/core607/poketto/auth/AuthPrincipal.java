package io.github.core607.poketto.auth;

import java.util.UUID;

/** Authenticated identity, not cached authorization; each operation must revalidate its workspace access. */
public final class AuthPrincipal {
    public enum Kind {
        ACCOUNT,
        API_KEY
    }

    private final Kind kind;
    private final UUID subjectId;
    private final UUID accountId;

    AuthPrincipal(Kind kind, UUID subjectId, UUID accountId) {
        this.kind = kind;
        this.subjectId = subjectId;
        this.accountId = accountId;
    }

    public Kind kind() {
        return kind;
    }

    public UUID subjectId() {
        return subjectId;
    }

    public UUID accountId() {
        return accountId;
    }

    @Override
    public String toString() {
        return kind + ":" + subjectId;
    }
}
