package io.github.core607.poketto.capture;

/** The sender used up its captures for this minute or day; nothing was stored. */
public final class CaptureLimitException extends RuntimeException {
    public CaptureLimitException() {
        super("capture limit reached");
    }
}
