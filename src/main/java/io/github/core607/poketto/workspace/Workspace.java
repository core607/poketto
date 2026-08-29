package io.github.core607.poketto.workspace;

import java.util.Objects;

/**
 * Catalog entry for a workspace. The display name is mutable metadata, not identity.
 */
public record Workspace(WorkspaceId id, String displayName) {

    public Workspace {
        Objects.requireNonNull(id, "workspace id must not be null");
        Objects.requireNonNull(displayName, "workspace display name must not be null");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("workspace display name must not be blank");
        }
    }
}
