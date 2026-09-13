package io.github.core607.poketto.executor.internal;

/** Bounded diagnostics for rejected local selections; never carries worker paths or exception text. */
final class InvalidSelectionException extends IllegalArgumentException {
    enum Reason {
        INVALID_ARGUMENTS,
        INVALID_PATH,
        SELECTION_LIMIT,
        PATH_COLLISION,
        NOT_FOUND,
        NOT_REGULAR_FILE,
        NOT_UTF8,
        TEXT_LIMIT,
        BINARY_LIMIT,
        FILE_CHANGED,
        CAPTURE_UNAVAILABLE,
        NO_WRITABLE_BASELINE
    }

    private final Reason reason;

    InvalidSelectionException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    static InvalidSelectionException capture(String reason) {
        try {
            return new InvalidSelectionException(Reason.valueOf(reason));
        } catch (IllegalArgumentException unknown) {
            return new InvalidSelectionException(Reason.CAPTURE_UNAVAILABLE);
        }
    }

    static String reason(IllegalArgumentException failure) {
        return failure instanceof InvalidSelectionException selection
                ? selection.reason.name()
                : Reason.INVALID_ARGUMENTS.name();
    }
}
