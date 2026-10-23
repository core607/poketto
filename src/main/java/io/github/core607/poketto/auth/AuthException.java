package io.github.core607.poketto.auth;

/** Stable failure codes; messages never include credentials or untrusted input. */
public final class AuthException extends RuntimeException {
    public enum Code {
        DENIED,
        INVALID_CREDENTIALS,
        INVALID_INVITATION,
        ALREADY_INITIALIZED,
        INVALID_INPUT,
        LAST_OWNER,
        LAST_ADMINISTRATOR,
        EMAIL_IN_USE,
        IDENTITY_IN_USE,
        LAST_LOGIN_METHOD,
        ACCOUNT_SUSPENDED,
        PROTECTED_ACCOUNT,
        APPLICATION_OPEN,
        APPLICATION_COOLDOWN,
        ALREADY_CREATOR
    }

    private final Code code;

    public AuthException(Code code) {
        super("Authentication operation failed: " + code);
        this.code = code;
    }

    public AuthException(Code code, Throwable cause) {
        super("Authentication operation failed: " + code, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
