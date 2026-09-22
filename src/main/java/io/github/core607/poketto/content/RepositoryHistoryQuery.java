package io.github.core607.poketto.content;

/** A literal path and a commit-scan offset, not an offset into matching changes. */
public record RepositoryHistoryQuery(String path, int offset, int limit) {
    public RepositoryHistoryQuery {
        RepositoryPaths.validate(path);
        if (offset < 0 || offset > 100_000) {
            throw new IllegalArgumentException("history offset must be between 0 and 100000");
        }
        if (limit < 1 || limit > 32) {
            throw new IllegalArgumentException("history page limit must be between 1 and 32");
        }
    }
}
