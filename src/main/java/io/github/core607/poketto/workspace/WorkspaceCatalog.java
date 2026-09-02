package io.github.core607.poketto.workspace;

import java.util.Optional;

/**
 * Instance-level workspace lookup. Workspace-owned domain operations still require an explicit
 * {@link WorkspaceId}; the default lookup is for entry-point initialization only.
 */
public interface WorkspaceCatalog {

    Workspace defaultWorkspace();

    Optional<Workspace> findById(WorkspaceId workspaceId);
}
