package io.github.core607.poketto.executor.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.RepositoryMoveService;

/** Supplies mocked content operations to signed-worker protocol fixtures. */
final class LegacyExecutorFixture {
    static SelectedFileSaves mockSaves() {
        SelectedFileSaves saves = mock(SelectedFileSaves.class);
        AuthorizedRepositoryReader reader = mock(AuthorizedRepositoryReader.class);
        when(saves.repository()).thenReturn(reader);
        when(saves.moves()).thenReturn(new WorkingCopyMoves(reader, mock(RepositoryMoveService.class)));
        return saves;
    }
}
