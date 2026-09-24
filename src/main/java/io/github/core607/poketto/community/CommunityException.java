package io.github.core607.poketto.community;

public final class CommunityException extends RuntimeException {
    private final Code code;

    public CommunityException(Code code) {
        super("Community operation failed: " + code);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        UNAVAILABLE,
        PARTICIPATION_REQUIRED,
        LIMIT_REACHED,
        REQUEST_CONFLICT,
        REPLY_UNAVAILABLE,
        DENIED,
        /** The article's body changed after the reader started the correction. */
        BASE_CHANGED
    }
}
