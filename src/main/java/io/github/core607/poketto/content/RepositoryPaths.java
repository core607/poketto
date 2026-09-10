package io.github.core607.poketto.content;

/** Validates bounded repository-relative names at module boundaries; never resolves a host filesystem path. */
public final class RepositoryPaths {
    private RepositoryPaths() {}

    public static String validate(String path) {
        return io.github.core607.poketto.content.internal.RepositoryPathRules.validate(path);
    }
}
