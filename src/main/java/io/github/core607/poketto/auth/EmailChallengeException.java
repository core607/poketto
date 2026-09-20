package io.github.core607.poketto.auth;

/** Stable errors contain neither an address nor a code. */
public final class EmailChallengeException extends RuntimeException {
    public enum Code {
        INVALID_INPUT,
        INVALID_CHALLENGE,
        RATE_LIMITED,
        DELIVERY_UNAVAILABLE
    }

    private final Code code;

    public EmailChallengeException(Code code) {
        super("Email verification failed: " + code);
        this.code = code;
    }

    public EmailChallengeException(Code code, Throwable cause) {
        super("Email verification failed: " + code, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
