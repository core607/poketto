package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Objects;
import java.util.Set;

/** Exact-commit media metadata; path eligibility is not a publication or read grant. */
public record RepositoryMediaSnapshot(
        WorkspaceId workspaceId, String commit, RepositoryMediaIndex index, Set<String> publicPaths) {
    public RepositoryMediaSnapshot {
        Objects.requireNonNull(workspaceId);
        Objects.requireNonNull(index);
        if (commit == null || !commit.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("exact media commit is required");
        }
        publicPaths = Set.copyOf(publicPaths);
        if (!index.files().keySet().containsAll(publicPaths)) {
            throw new IllegalArgumentException("public media paths must exist in the index");
        }
    }
}
