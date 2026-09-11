package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.content.RepositoryConnectionException;
import org.junit.jupiter.api.Test;

class ManagedRepositoryConnectionsTests {
    @Test
    void rotatingAnExistingBindingDoesNotDependOnOperatorCredentialsOrIdentity() throws Exception {
        var workspace = io.github.core607.poketto.workspace.WorkspaceId.random();
        var coordinates =
                io.github.core607.poketto.content.RepositoryCoordinates.parse("https://cnb.cool/example/notes");
        var cipher =
                new RepositoryCredentialCipher(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        var providers = mock(RepositoryProviderClient.class);
        when(providers.read(any(), any()))
                .thenReturn(new RepositoryProviderClient.Metadata(coordinates, "cnb:123", true));
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        byte[] old = cipher.encrypt(
                workspace, coordinates.canonicalUri(), new RepositoryCredentialCipher.Credentials("cnb", "old-token"));
        when(jdbc.query(
                        anyString(),
                        org.mockito.ArgumentMatchers
                                .<org.springframework.jdbc.core.RowMapper<
                                                io.github.core607.poketto.content.RepositoryConnections
                                                        .CredentialRotation>>
                                        any(),
                        eq(workspace.value())))
                .thenReturn(java.util.List.of(
                        new io.github.core607.poketto.content.RepositoryConnections.CredentialRotation(
                                workspace, coordinates.canonicalUri(), "cnb:123", old, old)));
        var connections = new ManagedRepositoryConnections(
                jdbc,
                cipher,
                providers,
                new RepositoryProperties(
                        coordinates.canonicalUri(), "operator", "unavailable-token", null, null, null, null));
        var transport = mock(org.eclipse.jgit.transport.TransportHttp.class);
        var fetch = mock(org.eclipse.jgit.transport.FetchConnection.class);
        when(fetch.getRefs()).thenReturn(java.util.List.of());
        when(transport.openFetch()).thenReturn(fetch);
        when(transport.openPush()).thenReturn(mock(org.eclipse.jgit.transport.PushConnection.class));
        try (var factory = mockStatic(org.eclipse.jgit.transport.Transport.class)) {
            factory.when(() -> org.eclipse.jgit.transport.Transport.open(
                            any(org.eclipse.jgit.lib.Repository.class), any(org.eclipse.jgit.transport.URIish.class)))
                    .thenReturn(transport);
            var rotation = connections.prepareRotation(workspace, "cnb", "new-token");
            assertThat(cipher.decrypt(workspace, coordinates.canonicalUri(), rotation.replacementCredentials())
                            .password())
                    .isEqualTo("new-token");
            verify(providers).read(argThat(value -> value.canonicalUri().equals(coordinates.canonicalUri())), any());
            verifyNoMoreInteractions(providers);
            assertThatThrownBy(() -> connections.verify(workspace, coordinates, old))
                    .isInstanceOfSatisfying(
                            RepositoryConnectionException.class,
                            error -> assertThat(error.code()).isEqualTo(RepositoryConnectionException.Code.DUPLICATE));
        }
    }

    @Test
    void otherOperatorProvidersDoNotBlockSupportedManagedConnections() {
        assertThat(ManagedRepositoryConnections.comparableOperator("https://git.example.com/owner/private-content.git"))
                .isNull();
        assertThat(ManagedRepositoryConnections.comparableOperator("https://GITHUB.com/Owner/Notes.git")
                        .canonicalUri())
                .isEqualTo("https://github.com/owner/notes");
        assertThatThrownBy(() -> ManagedRepositoryConnections.comparableOperator("https://github.com/"))
                .isInstanceOf(RepositoryConnectionException.class);
    }
}
