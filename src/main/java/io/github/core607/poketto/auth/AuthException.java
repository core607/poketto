package io.github.core607.poketto.auth;

/** Stable failure codes; messages never include credentials or untrusted input. */
public final class AuthException extends RuntimeException {
    public enum Code {
        DENIED,
        INVALID_CREDENTIALS,
        INVALID_INVITATION,
        ALREADY_INITIALIZED,
        INVALID_INPUT,
        LAST_OWNER
    }

    private final Code code;

    AuthException(Code code) {
        super("Authentication operation failed: " + code);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
