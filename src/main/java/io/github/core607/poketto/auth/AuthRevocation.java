package io.github.core607.poketto.auth;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Set;
import java.util.UUID;

/** Published after commit so execution owners can terminate affected sessions and descendants. */
public record AuthRevocation(WorkspaceId workspaceId, Set<UUID> accountIds, Set<UUID> apiKeyIds) {
    public AuthRevocation {
        accountIds = Set.copyOf(accountIds);
        apiKeyIds = Set.copyOf(apiKeyIds);
    }
}
