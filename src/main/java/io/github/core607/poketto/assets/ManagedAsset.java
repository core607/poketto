package io.github.core607.poketto.assets;

import java.util.Objects;

public record ManagedAsset(ManagedAssetReference reference, String mediaType, long size) {
    public ManagedAsset {
        Objects.requireNonNull(reference, "asset reference is required");
        if (!java.util.Set.of("image/png", "image/jpeg", "image/gif", "image/webp")
                        .contains(mediaType)
                || size <= 0
                || size > ManagedBlobStore.MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("invalid managed image metadata");
        }
    }
}
