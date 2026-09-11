package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.RepositoryConnectionException;
import io.github.core607.poketto.content.RepositoryConnections;
import io.github.core607.poketto.content.RepositoryCoordinates;
import io.github.core607.poketto.workspace.WorkspaceId;
import org.springframework.jdbc.core.JdbcTemplate;

/** Synthetic provider boundary for browser acceptance; encryption, binding writes and CAS use production code. */
final class AcceptanceManagedConnections implements RepositoryConnections, AutoCloseable {
    private final JdbcTemplate jdbc;
    private final RepositoryCredentialCipher cipher;
    private final ManagedRepositoryConnections delegate;

    AcceptanceManagedConnections(JdbcTemplate jdbc, String key) {
        this.jdbc = jdbc;
        this.cipher = new RepositoryCredentialCipher(key);
        this.delegate = new ManagedRepositoryConnections(
                jdbc,
                cipher,
                new RepositoryProviderClient(),
                new RepositoryProperties(null, null, null, null, null, null, null));
    }

    public boolean available() {
        return delegate.available();
    }

    public java.util.Optional<ConnectionInfo> connectionInfo(WorkspaceId workspace) {
        return delegate.connectionInfo(workspace);
    }

    public byte[] seal(WorkspaceId workspace, RepositoryCoordinates coordinates, String username, String token) {
        return delegate.seal(workspace, coordinates, username, token);
    }

    public Verified verify(WorkspaceId workspace, RepositoryCoordinates coordinates, byte[] sealed) {
        var credentials = cipher.decrypt(workspace, coordinates.canonicalUri(), sealed);
        if (!coordinates.canonicalUri().equals("https://github.com/example/acceptance")
                || !credentials.username().equals("fixture")
                || !java.util.Set.of("fixture-token-initial", "fixture-token-replacement")
                        .contains(credentials.password()))
            throw new RepositoryConnectionException(RepositoryConnectionException.Code.PERMISSION_DENIED);
        return new Verified("github:acceptance-fixture", true);
    }

    public void install(WorkspaceId workspace, RepositoryCoordinates coordinates, byte[] sealed, Verified verified) {
        delegate.install(workspace, coordinates, sealed, verified);
    }

    public CredentialRotation prepareRotation(WorkspaceId workspace, String username, String token) {
        var before = jdbc.queryForMap(
                "select canonical_uri,provider_identity,sealed_credentials from content_repository_bindings where workspace_id=?",
                workspace.value());
        var coordinates = RepositoryCoordinates.parse((String) before.get("canonical_uri"));
        byte[] replacement = seal(workspace, coordinates, username, token);
        var verified = verify(workspace, coordinates, replacement);
        if (!verified.providerIdentity().equals(before.get("provider_identity")))
            throw new RepositoryConnectionException(RepositoryConnectionException.Code.REPOSITORY_CHANGED);
        return new CredentialRotation(
                workspace,
                coordinates.canonicalUri(),
                verified.providerIdentity(),
                (byte[]) before.get("sealed_credentials"),
                replacement);
    }

    public void applyRotation(WorkspaceId workspace, CredentialRotation rotation) {
        delegate.applyRotation(workspace, rotation);
    }

    public void close() {
        delegate.close();
    }
}
