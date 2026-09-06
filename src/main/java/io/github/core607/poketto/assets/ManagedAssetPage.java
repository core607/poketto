package io.github.core607.poketto.assets;

import java.util.List;

public record ManagedAssetPage(List<ManagedAsset> items, int total, int offset, int limit) {
    public ManagedAssetPage {
        items = List.copyOf(items);
    }
}
