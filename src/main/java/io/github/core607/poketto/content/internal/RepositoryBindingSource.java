package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.workspace.WorkspaceId;

@FunctionalInterface
interface RepositoryBindingSource {

    RepositoryBinding bindingFor(WorkspaceId workspaceId);
}
