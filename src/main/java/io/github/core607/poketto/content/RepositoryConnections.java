package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;

/** Validates and installs remote bindings inside the caller's workspace-creation transaction. */
public interface RepositoryConnections {
    boolean available();

    byte[] seal(WorkspaceId workspace, RepositoryCoordinates coordinates, String username, String token);

    Verified verify(WorkspaceId workspace, RepositoryCoordinates coordinates, byte[] sealedCredentials);

    void install(WorkspaceId workspace, RepositoryCoordinates coordinates, byte[] sealedCredentials, Verified verified);

    /** Does not reveal or replace the repository location. The caller must hold current owner authorization. */
    void rotate(WorkspaceId workspace, String username, String token);

    record Verified(String providerIdentity, boolean privateRepository) {
        @Override
        public String toString() {
            return "VerifiedRepository[redacted]";
        }
    }
}
