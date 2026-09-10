package io.github.core607.poketto.content;

/** Bounded export status without content paths, storage coordinates or another workspace's metadata. */
public final class ContentExportException extends RuntimeException {
    public enum Reason {
        CAPACITY,
        NOT_FOUND,
        UNAVAILABLE
    }

    private final Reason reason;

    public ContentExportException(Reason reason) {
        super("content export " + reason.name().toLowerCase(java.util.Locale.ROOT));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
