package io.github.core607.poketto.assets;

import java.util.Objects;

/** Bounded exact bytes plus the resolved immutable source for MCP and HTTP adapters. */
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
