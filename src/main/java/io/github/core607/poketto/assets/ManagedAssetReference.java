package io.github.core607.poketto.assets;

import java.util.Objects;
import java.util.UUID;

/** An immutable workspace-local identity and SHA-256 revision, with no storage location. */
public record ManagedAssetReference(UUID assetId, String revision) {
    public ManagedAssetReference {
        Objects.requireNonNull(assetId, "asset identity is required");
        if (revision == null || !revision.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("asset revision must be a lowercase SHA-256 digest");
        }
    }

    @Override
    public String toString() {
        return "managed:" + assetId + ":" + revision;
    }
}
