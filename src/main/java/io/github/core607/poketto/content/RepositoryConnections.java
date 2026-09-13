package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Instant;
import java.util.Optional;

/** Network validation runs without a transaction; binding mutations require the caller's authorized transaction. */
public interface RepositoryConnections {
    boolean available();

    /** Owner-facing binding metadata only; never decrypts or returns provider credentials. */
    Optional<ConnectionInfo> connectionInfo(WorkspaceId workspace);

    record ConnectionInfo(String repository, Instant updatedAt) {}

    byte[] seal(WorkspaceId workspace, RepositoryCoordinates coordinates, String username, String token);

    Verified verify(WorkspaceId workspace, RepositoryCoordinates coordinates, byte[] sealedCredentials);

    void install(WorkspaceId workspace, RepositoryCoordinates coordinates, byte[] sealedCredentials, Verified verified);

    /** Performs network validation without a database transaction or workspace lock. */
    CredentialRotation prepareRotation(WorkspaceId workspace, String username, String token);

    /** Commits only if the binding still matches. The caller holds current human-owner authorization. */
    void applyRotation(WorkspaceId workspace, CredentialRotation rotation);

    record CredentialRotation(
            WorkspaceId workspace,
            String canonicalUri,
            String providerIdentity,
            byte[] previousCredentials,
            byte[] replacementCredentials) {
        public CredentialRotation {
            previousCredentials = previousCredentials.clone();
            replacementCredentials = replacementCredentials.clone();
        }

        @Override
        public byte[] previousCredentials() {
            return previousCredentials.clone();
        }

        @Override
        public byte[] replacementCredentials() {
            return replacementCredentials.clone();
        }

        @Override
        public String toString() {
            return "CredentialRotation[redacted]";
        }
    }

    record Verified(String providerIdentity, boolean privateRepository) {
        @Override
        public String toString() {
            return "VerifiedRepository[redacted]";
        }
    }
}
