package io.github.core607.poketto.content;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;

/** Shared browser/MCP mutation boundary; authorization is revalidated for every operation. */
public interface RepositoryPatchService {
    RepositoryPatchResult apply(AuthPrincipal principal, WorkspaceId workspace, RepositoryPatch patch);
}
