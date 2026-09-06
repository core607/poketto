package io.github.core607.poketto.assets;

/** Storage errors contain no caller path or physical storage coordinate. */
public final class AssetStorageException extends RuntimeException {
    public enum Reason {
        INVALID_IMAGE,
        TOO_LARGE,
        IDEMPOTENCY_CONFLICT,
        NOT_FOUND,
        UNAVAILABLE
    }

    private final Reason reason;

    public AssetStorageException(Reason reason) {
        super(
                switch (reason) {
                    case INVALID_IMAGE -> "image format or dimensions are unsupported";
                    case TOO_LARGE -> "image exceeds the 16 MiB upload bound";
                    case IDEMPOTENCY_CONFLICT -> "upload operation key already identifies different bytes";
                    case NOT_FOUND -> "managed image revision is unavailable";
                    case UNAVAILABLE ->
                        "managed image storage requires a trusted local filesystem with atomic moves and directory fsync";
                });
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
