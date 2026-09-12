package io.github.core607.poketto.assets;

import io.github.core607.poketto.content.RepositoryDiagnostic;
import java.util.List;

/**
 * One bounded page of images found in a repository tree, pinned to the commit it was read
 * from. Entries that could not be read arrive as diagnostics rather than being dropped, so
 * a partial tree cannot look like a complete one.
 */
public record RepositoryImagePage(
        String commit, List<Item> items, int total, int offset, int limit, List<RepositoryDiagnostic> diagnostics) {
    public RepositoryImagePage {
        items = List.copyOf(items);
        diagnostics = List.copyOf(diagnostics);
    }

    public record Item(String path, String mediaType, long size) {}
}
