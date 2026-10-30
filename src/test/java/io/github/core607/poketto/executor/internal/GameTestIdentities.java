package io.github.core607.poketto.executor.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.games.GameRunner;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.UUID;

/** Native execution probes use synthetic identities; account authorization is verified by application tests. */
final class GameTestIdentities {
    private GameTestIdentities() {}

    static GameRunner.Player player(WorkspaceId workspace) {
        var principal = mock(AuthPrincipal.class);
        when(principal.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(principal.subjectId()).thenReturn(UUID.randomUUID());
        when(principal.accountId()).thenReturn(UUID.randomUUID());
        return new GameRunner.Player(principal, workspace);
    }
}
