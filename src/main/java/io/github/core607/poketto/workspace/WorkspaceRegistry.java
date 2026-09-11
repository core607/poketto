package io.github.core607.poketto.workspace;

/** Catalog mutations participate in the account owner's creation transaction. */
public interface WorkspaceRegistry {
    void create(WorkspaceId id, String displayName, String publicSlug);
}
