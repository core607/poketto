package io.github.core607.poketto.content;

/**
 * Reports that the workspace worktree or index differs from {@code HEAD}. The owner may edit a
 * content repository directly, so a machine write refuses rather than committing or overwriting
 * bytes it did not produce. The message names what must be committed or reverted first.
 */
public final class RepositoryNotCleanException extends RuntimeException {

    public RepositoryNotCleanException(String message) {
        super(message);
    }
}
