package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.workspace.WorkspaceId;

/** Immutable source lookup under the admitted copy lock; callers enforce current authorization. */
@FunctionalInterface
interface OriginalFileLookup {
    RepositoryFile file(AuthPrincipal actor, WorkspaceId workspace, String commit, String path);
}
