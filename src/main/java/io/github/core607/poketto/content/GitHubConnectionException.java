package io.github.core607.poketto.content;

/** Provider failures expose fixed codes, never provider response bodies or credentials. */
public final class GitHubConnectionException extends RuntimeException {
    public enum Code {
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

    public GitHubConnectionException(Code code) {
        super("GitHub App: " + code.name());
        this.code = code;
    }

    public GitHubConnectionException(Code code, Throwable cause) {
        super("GitHub App: " + code.name(), cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
