package io.github.core607.poketto.content;

/**
 * The repository has no commit on main yet, so no copy or projection can be opened from it. The
 * owner initializes it from the space's repository connection; until then the condition is named
 * rather than reported as an unavailable authority.
 */
public final class RepositoryEmptyException extends RuntimeException {
    public RepositoryEmptyException() {
        super("repository has no commit");
    }
}
