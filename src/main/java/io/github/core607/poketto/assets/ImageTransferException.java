package io.github.core607.poketto.assets;

/** Bounded transfer failures safe to expose without source URLs or grant secrets. */
public final class ImageTransferException extends RuntimeException {
    public enum Reason {
        SOURCE_UNAVAILABLE,
        UPLOAD_EXPIRED,
        TRANSFER_BUSY,
        IMAGE_MEMORY_BUSY,
        UPLOAD_PENDING
    }

    private final Reason reason;

    public ImageTransferException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public ImageTransferException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
