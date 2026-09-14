package io.github.core607.poketto.content;

import java.util.List;

/** Authorized regular Git files and indexed originals; names do not imply text readability. */
public record RepositoryFilenamePage(String commit, List<String> paths, int total, int offset, int limit) {
    public RepositoryFilenamePage {
        paths = List.copyOf(paths);
    }
}
