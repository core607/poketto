package io.github.core607.poketto.content.internal;

/** Provider failures expose fixed codes, never provider response bodies or credentials. */
final class GitHubAppFailure extends RuntimeException {
    enum Code {
        AUTHORIZATION_REQUIRED,
        AUTHORIZATION_CHANGED,
        BUSY,
        UNAVAILABLE,
        INVALID_RESPONSE,
        IDENTITY_CHANGED,
        CREATION_REJECTED,
        CREATION_UNCERTAIN,
        REPOSITORY_CHANGED,
        INSTALLATION_REQUIRED
    }

    private final Code code;

    GitHubAppFailure(Code code) {
        super("GitHub App: " + code.name());
        this.code = code;
    }

    GitHubAppFailure(Code code, Throwable cause) {
        super("GitHub App: " + code.name(), cause);
        this.code = code;
    }

    Code code() {
        return code;
    }
}
