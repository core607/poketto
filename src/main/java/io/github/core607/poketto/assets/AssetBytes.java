package io.github.core607.poketto.assets;

import java.util.Objects;

/** Bounded delivered bytes plus the immutable source and exact-byte or derivative revision. */
public record AssetBytes(AssetSource source, String revision, String mediaType, byte[] bytes) {
    public AssetBytes {
        Objects.requireNonNull(source);
        Objects.requireNonNull(mediaType);
        Objects.requireNonNull(revision);
        if (bytes.length > ManagedBlobStore.MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("image response exceeds its bound");
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public int size() {
        return bytes.length;
    }
}
