package io.github.core607.poketto.content.internal;

import static io.github.core607.poketto.content.RepositoryConnectionException.Code.*;

import io.github.core607.poketto.content.RepositoryConnectionException;
import io.github.core607.poketto.content.RepositoryConnections;
import io.github.core607.poketto.content.RepositoryCoordinates;
import io.github.core607.poketto.workspace.WorkspaceId;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

final class ManagedRepositoryConnections implements RepositoryConnections, AutoCloseable {
    private final JdbcTemplate jdbc;
    private final RepositoryCredentialCipher cipher;
    private final RepositoryProviderClient providers;
    private final RepositoryProperties configured;

    ManagedRepositoryConnections(
            JdbcTemplate jdbc,
            RepositoryCredentialCipher cipher,
            RepositoryProviderClient providers,
            RepositoryProperties configured) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.providers = providers;
        this.configured = configured;
    }

    public boolean available() {
        return cipher.available();
    }

    public byte[] seal(WorkspaceId workspace, RepositoryCoordinates coordinates, String username, String token) {
        return cipher.encrypt(
                workspace, coordinates.canonicalUri(), new RepositoryCredentialCipher.Credentials(username, token));
    }

    public Verified verify(WorkspaceId workspace, RepositoryCoordinates coordinates, byte[] sealedCredentials) {
        return verify(workspace, coordinates, sealedCredentials, true);
    }

    private Verified verify(
            WorkspaceId workspace, RepositoryCoordinates coordinates, byte[] sealedCredentials, boolean creating) {
        var credentials = cipher.decrypt(workspace, coordinates.canonicalUri(), sealedCredentials);
        var metadata = providers.read(coordinates, credentials);
        if (!metadata.privateRepository()) throw new RepositoryConnectionException(PRIVATE_REPOSITORY_REQUIRED);
        if (creating) rejectDefaultDuplicate(metadata);
        try (var repository = new InMemoryRepository(new DfsRepositoryDescription());
                Transport transport = Transport.open(repository, new URIish(coordinates.transportUri()))) {
            transport.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider(credentials.username(), credentials.password()));
            transport.setTimeout(30);
            ((TransportHttp) transport).setHttpConnectionFactory(new ManagedGitHttp(coordinates.transportUri()));
            try (var fetch = transport.openFetch()) {
                boolean hasBranches =
                        fetch.getRefs().stream().anyMatch(ref -> ref.getName().startsWith("refs/heads/"));
                if (hasBranches && fetch.getRef("refs/heads/main") == null)
                    throw new RepositoryConnectionException(INVALID_INPUT);
            }
            // Receive-pack advertisement checks Git write access without changing any remote ref.
            try (var push = transport.openPush()) {
                push.getRefs();
            }
        } catch (RepositoryConnectionException expected) {
            throw expected;
        } catch (Exception unavailable) {
            throw new RepositoryConnectionException(UNAVAILABLE);
        }
        return new Verified(metadata.identity(), metadata.privateRepository());
    }

    private void rejectDefaultDuplicate(RepositoryProviderClient.Metadata candidate) {
        RepositoryCoordinates operator = comparableOperator(configured.remoteUri());
        if (operator == null) return;
        // The operator binding is authoritative too. Resolve its immutable identity so a rename
        // or provider-side redirect cannot make it look like an unrelated managed repository.
        if (operator.canonicalUri().equals(candidate.coordinates().canonicalUri()))
            throw new RepositoryConnectionException(DUPLICATE);
        if (!operator.provider().equals(candidate.coordinates().provider())) return;
        var existing = providers.read(
                operator, new RepositoryCredentialCipher.Credentials(configured.username(), configured.password()));
        if (existing.identity().equals(candidate.identity())) throw new RepositoryConnectionException(DUPLICATE);
    }

    static RepositoryCoordinates comparableOperator(String remote) {
        if (remote == null) return null;
        try {
            String host = java.net.URI.create(remote).getHost();
            if (host != null
                    && !java.util.Set.of("github.com", "cnb.cool").contains(host.toLowerCase(java.util.Locale.ROOT)))
                return null;
            return RepositoryCoordinates.parse(remote);
        } catch (IllegalArgumentException invalid) {
            throw new RepositoryConnectionException(UNAVAILABLE);
        }
    }

    public void install(
            WorkspaceId workspace, RepositoryCoordinates coordinates, byte[] sealedCredentials, Verified verified) {
        requireTransaction();
        // Authenticate the envelope again before committing a binding supplied by another module.
        cipher.decrypt(workspace, coordinates.canonicalUri(), sealedCredentials);
        jdbc.update(
                "insert into content_repository_bindings(workspace_id,canonical_uri,provider_identity,sealed_credentials) values (?,?,?,?)",
                workspace.value(),
                coordinates.canonicalUri(),
                verified.providerIdentity(),
                sealedCredentials);
    }

    public CredentialRotation prepareRotation(WorkspaceId workspace, String username, String token) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Credential validation must run outside a database transaction");
        var rows = jdbc.query(
                "select canonical_uri,provider_identity,sealed_credentials from content_repository_bindings where workspace_id=?",
                (row, number) -> new CredentialRotation(
                        workspace, row.getString(1), row.getString(2), row.getBytes(3), row.getBytes(3)),
                workspace.value());
        if (rows.isEmpty()) throw new RepositoryConnectionException(UNAVAILABLE);
        var before = rows.getFirst();
        var coordinates = RepositoryCoordinates.parse(before.canonicalUri());
        byte[] sealed = seal(workspace, coordinates, username, token);
        var verified = verify(workspace, coordinates, sealed, false);
        if (!verified.providerIdentity().equals(before.providerIdentity()))
            throw new RepositoryConnectionException(REPOSITORY_CHANGED);
        return new CredentialRotation(
                workspace, before.canonicalUri(), before.providerIdentity(), before.previousCredentials(), sealed);
    }

    public void applyRotation(WorkspaceId workspace, CredentialRotation rotation) {
        requireTransaction();
        if (!workspace.equals(rotation.workspace())) throw new RepositoryConnectionException(REPOSITORY_CHANGED);
        cipher.decrypt(workspace, rotation.canonicalUri(), rotation.replacementCredentials());
        int changed = jdbc.update(
                "update content_repository_bindings set sealed_credentials=?,updated_at=current_timestamp where workspace_id=? and canonical_uri=? and provider_identity=? and sealed_credentials=?",
                rotation.replacementCredentials(),
                workspace.value(),
                rotation.canonicalUri(),
                rotation.providerIdentity(),
                rotation.previousCredentials());
        if (changed != 1) throw new RepositoryConnectionException(REPOSITORY_CHANGED);
    }

    RepositoryBinding binding(WorkspaceId workspace) {
        var rows = jdbc.query(
                "select canonical_uri,sealed_credentials from content_repository_bindings where workspace_id=?",
                (row, number) -> {
                    var coordinates = RepositoryCoordinates.parse(row.getString(1));
                    var credentials = cipher.decrypt(workspace, coordinates.canonicalUri(), row.getBytes(2));
                    try {
                        return new RepositoryBinding(
                                new URIish(coordinates.transportUri()),
                                new UsernamePasswordCredentialsProvider(credentials.username(), credentials.password()),
                                true);
                    } catch (java.net.URISyntaxException invalid) {
                        throw new RepositoryConnectionException(UNAVAILABLE);
                    }
                },
                workspace.value());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Repository binding mutation requires a workspace transaction");
    }

    @Override
    public void close() {
        providers.close();
    }
}
