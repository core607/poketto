package io.github.core607.poketto.assets;

import java.util.List;

/** One bounded page of stored originals, with the total so a caller can page without rescanning. */
public record ManagedAssetPage(List<ManagedAsset> items, int total, int offset, int limit) {
    public ManagedAssetPage {
        items = List.copyOf(items);
    }
}
