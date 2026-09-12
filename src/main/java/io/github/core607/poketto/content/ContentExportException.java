package io.github.core607.poketto.content;

import java.util.Locale;

/** Bounded export status without content paths, storage coordinates or another workspace's metadata. */
public final class ContentExportException extends RuntimeException {
    public enum Reason {
        CAPACITY,
        NOT_FOUND,
        UNAVAILABLE
    }

    private final Reason reason;

    public ContentExportException(Reason reason) {
        super("content export " + reason.name().toLowerCase(Locale.ROOT));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
