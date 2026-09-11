package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.*;

import io.github.core607.poketto.content.RepositoryConnectionException;
import org.junit.jupiter.api.Test;

class ManagedRepositoryConnectionsTests {
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
