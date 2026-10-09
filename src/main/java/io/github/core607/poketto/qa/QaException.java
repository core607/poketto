package io.github.core607.poketto.qa;

public final class QaException extends RuntimeException {
    private final String code;

    public QaException(String code, String message) {
        super(message);
        this.code = code;
    }

    public QaException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
