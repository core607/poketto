package io.github.core607.poketto.assets;

import java.util.Optional;

/** Client selection before authentication and authoritative commit resolution. */
public sealed interface AssetSource permits AssetSource.Managed, AssetSource.Repository {
    record Managed(ManagedAssetReference reference) implements AssetSource {
        public Managed {
            java.util.Objects.requireNonNull(reference);
        }
    }

    record Repository(Optional<String> commit, String path) implements AssetSource {
        public Repository {
            java.util.Objects.requireNonNull(commit);
            java.util.Objects.requireNonNull(path);
        }
    }
}
