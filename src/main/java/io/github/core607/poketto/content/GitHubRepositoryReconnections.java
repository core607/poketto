package io.github.core607.poketto.content;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Objects;

/** Reconnection preserves the original account, owner and immutable repository identity. */
public interface GitHubRepositoryReconnections {
    Status status(AuthPrincipal actor, WorkspaceId workspace);

    /** Performs provider validation outside the caller's authorization transaction. */
    Prepared prepare(AuthPrincipal actor, WorkspaceId workspace, String repositoryName);

    /** Applies a current proof inside the caller's account and workspace authorization transaction. */
    void apply(AuthPrincipal actor, WorkspaceId workspace, Prepared prepared);

    record Status(boolean authorizingAccount, boolean revoked) {}

    record Prepared(WorkspaceId workspace, long bindingVersion, GitHubRepositoryProvisioning.PreparedBinding binding) {
        public Prepared {
            Objects.requireNonNull(workspace, "Workspace is required");
            Objects.requireNonNull(binding, "Verified repository binding is required");
            if (bindingVersion <= 0) {
                throw new IllegalArgumentException("A positive GitHub binding version is required");
            }
        }

        @Override
        public String toString() {
            return "PreparedGitHubReconnection[redacted]";
        }
    }
}
