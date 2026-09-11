package io.github.core607.poketto.assets;

import java.util.Objects;
import java.util.Optional;

/** Client selection before authentication and authoritative commit resolution. */
public sealed interface AssetSource permits AssetSource.Managed, AssetSource.Repository {
    record Managed(ManagedAssetReference reference) implements AssetSource {
        public Managed {
            Objects.requireNonNull(reference);
        }
    }

    record Repository(Optional<String> commit, String path) implements AssetSource {
        public Repository {
            Objects.requireNonNull(commit);
            Objects.requireNonNull(path);
        }
    }
}
