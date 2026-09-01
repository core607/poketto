package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;

final class ConfiguredRepositoryBindingSource implements RepositoryBindingSource {

    private final RepositoryProperties properties;
    private final ObjectProvider<WorkspaceCatalog> workspaces;

    ConfiguredRepositoryBindingSource(
            RepositoryProperties properties, ObjectProvider<WorkspaceCatalog> workspaces) {
        this.properties = Objects.requireNonNull(properties, "repository properties must not be null");
        this.workspaces = Objects.requireNonNull(workspaces, "workspace catalog provider must not be null");
    }

    @Override
    public RepositoryBinding bindingFor(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspace id must not be null");
        WorkspaceCatalog catalog = workspaces.getIfAvailable();
        if (catalog == null || !catalog.defaultWorkspace().id().equals(workspaceId)) {
            throw new ContentRepositoryException(
                    "workspace " + workspaceId + " has no provisioned remote repository binding");
        }
        return properties.requiredBinding();
    }
}
