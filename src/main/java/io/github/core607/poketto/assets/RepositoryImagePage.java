package io.github.core607.poketto.assets;

import java.util.List;

public record RepositoryImagePage(
        String commit,
        List<Item> items,
        int total,
        int offset,
        int limit,
        List<io.github.core607.poketto.content.RepositoryDiagnostic> diagnostics) {
    public RepositoryImagePage {
        items = List.copyOf(items);
        diagnostics = List.copyOf(diagnostics);
    }

    public record Item(String path, String mediaType, long size) {}
}
