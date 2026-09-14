package io.github.core607.poketto.content;

/** Literal matching of repository-relative filenames, with commit-pinned result pages. */
public record RepositoryFilenameSearch(String query, int offset, int limit) {
    public static final int MAX_ENTRIES = 100_000;

    public RepositoryFilenameSearch {
        if (query == null || query.isEmpty() || query.length() > DocumentSearch.MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("Filename query must contain between 1 and 200 characters");
        }
        if (offset < 0 || offset > MAX_ENTRIES) {
            throw new IllegalArgumentException("Filename search offset must be between 0 and 100000");
        }
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("Filename search limit must be between 1 and 200");
        }
    }
}
