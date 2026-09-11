package io.github.core607.poketto.content;

/** Fixed connection failures may cross the HTTP boundary without remote responses or credentials. */
public final class RepositoryConnectionException extends RuntimeException {
    public enum Code {
        INVALID_INPUT,
        BUSY,
        PRIVATE_REPOSITORY_REQUIRED,
        UNAVAILABLE,
        PERMISSION_DENIED,
        REPOSITORY_CHANGED,
        DUPLICATE
    }

    private final Code code;

    public RepositoryConnectionException(Code code) {
        super("Repository connection: " + code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
