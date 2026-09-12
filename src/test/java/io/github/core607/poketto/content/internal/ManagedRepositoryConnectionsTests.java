package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.content.RepositoryConnectionException;
import io.github.core607.poketto.content.RepositoryConnections;
import io.github.core607.poketto.content.RepositoryCoordinates;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Base64;
import java.util.List;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.PushConnection;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ManagedRepositoryConnectionsTests {
    @Test
    void rotatingAnExistingBindingDoesNotDependOnOperatorCredentialsOrIdentity() throws Exception {
        var workspace = WorkspaceId.random();
        var coordinates = RepositoryCoordinates.parse("https://cnb.cool/example/notes");
        var cipher = new RepositoryCredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));
        var providers = mock(RepositoryProviderClient.class);
        when(providers.read(any(), any()))
                .thenReturn(new RepositoryProviderClient.Metadata(coordinates, "cnb:123", true));
        var jdbc = mock(JdbcTemplate.class);
        byte[] old = cipher.encrypt(
                workspace, coordinates.canonicalUri(), new RepositoryCredentialCipher.Credentials("cnb", "old-token"));
        when(jdbc.query(
                        anyString(),
                        ArgumentMatchers.<RowMapper<RepositoryConnections.CredentialRotation>>any(),
                        eq(workspace.value())))
                .thenReturn(List.of(new RepositoryConnections.CredentialRotation(
                        workspace, coordinates.canonicalUri(), "cnb:123", old, old)));
        var connections = new ManagedRepositoryConnections(
                jdbc,
                cipher,
                providers,
                new RepositoryProperties(
                        coordinates.canonicalUri(), "operator", "unavailable-token", null, null, null, null));
        var transport = mock(TransportHttp.class);
        var fetch = mock(FetchConnection.class);
        when(fetch.getRefs()).thenReturn(List.of());
        when(transport.openFetch()).thenReturn(fetch);
        when(transport.openPush()).thenReturn(mock(PushConnection.class));
        try (var factory = mockStatic(Transport.class)) {
            factory.when(() -> Transport.open(any(Repository.class), any(URIish.class)))
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
