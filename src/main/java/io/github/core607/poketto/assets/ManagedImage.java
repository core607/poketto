package io.github.core607.poketto.assets;

/** Bounded exact original bytes. Array access never exposes mutable store-owned memory. */
public record ManagedImage(ManagedAsset asset, byte[] bytes) {
    public ManagedImage {
        java.util.Objects.requireNonNull(asset, "asset metadata is required");
        bytes = bytes.clone();
        if (bytes.length != asset.size()) {
            throw new IllegalArgumentException("image size does not match metadata");
        }
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
