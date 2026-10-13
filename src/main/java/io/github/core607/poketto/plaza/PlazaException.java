package io.github.core607.poketto.plaza;

/** Public action failures contain no repository or credential details. */
public final class PlazaException extends RuntimeException {
    private final String code;
    private final String next;

    public PlazaException(String code, String message, String next) {
        super(message);
        this.code = code;
        this.next = next;
    }

    public String code() {
        return code;
    }

    public String next() {
        return next;
    }
}
