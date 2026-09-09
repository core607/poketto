package io.github.core607.poketto.assets;

import java.util.Objects;

public record ManagedAsset(ManagedAssetReference reference, String mediaType, long size) {
    public ManagedAsset {
        Objects.requireNonNull(reference, "asset reference is required");
        validateMediaType(mediaType);
        if (size <= 0 || size > ManagedBlobStore.MAX_FILE_BYTES) {
            throw new IllegalArgumentException("invalid managed file metadata");
        }
    }

    public static void validateMediaType(String mediaType) {
        if (mediaType == null
                || !mediaType.matches("[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}")) {
            throw new IllegalArgumentException("invalid managed file media type");
        }
    }
}
