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
    private final long credentialVersion;

    AuthPrincipal(Kind kind, UUID subjectId, UUID accountId, long credentialVersion) {
        this.kind = kind;
        this.subjectId = subjectId;
        this.accountId = accountId;
        this.credentialVersion = credentialVersion;
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

    public long credentialVersion() {
        return credentialVersion;
    }

    @Override
    public String toString() {
        return kind + ":" + subjectId;
    }
}
