package io.github.core607.poketto.auth;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Set;

/** Authorization at one instant. Revalidate for subsequent requests; do not persist as a session authority. */
public record WorkspaceAccess(
        WorkspaceId workspaceId, AuthPrincipal principal, MembershipRole role, Set<Capability> capabilities) {
    public WorkspaceAccess {
        capabilities = Set.copyOf(capabilities);
    }
}
