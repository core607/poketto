package io.github.core607.poketto.content;

import io.github.core607.poketto.content.internal.RepositoryPathRules;

/** Validates bounded repository-relative names at module boundaries; never resolves a host filesystem path. */
public final class RepositoryPaths {
    private RepositoryPaths() {}

    public static String validate(String path) {
        return RepositoryPathRules.validate(path);
    }
}
