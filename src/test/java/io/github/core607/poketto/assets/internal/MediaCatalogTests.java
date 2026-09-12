package io.github.core607.poketto.assets.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.assets.MediaFileService;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositoryMediaSnapshot;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MediaCatalogTests {
    @Test
    void historicalCatalogUsesWorkspaceAuthorityAndDoesNotLoadOriginals() {
        var auth = mock(AuthService.class);
        var actor = mock(AuthPrincipal.class);
        var workspace = WorkspaceId.random();
        var repository = mock(RepositoryBlobReader.class);
        var expected = new RepositoryMediaSnapshot(workspace, "a".repeat(40), RepositoryMediaIndex.empty(), Set.of());
        when(repository.selectCommit(workspace, Optional.of(expected.commit())))
                .thenReturn(Optional.of(expected.commit()));
        doReturn(expected).when(repository).media(workspace, expected.commit());
        var service = new MediaFileService(auth, repository, mock(PublicContentSnapshots.class), () -> {
            throw new AssertionError("Listing must not open original storage");
        });
        assertThat(service.privateCatalog(actor, workspace, Optional.of(expected.commit())))
                .isSameAs(expected);
        verify(auth, times(2)).authorize(actor, workspace, Capability.READ_PRIVATE);
        verify(repository, never()).read(any());
    }

    @Test
    void revocationDuringCatalogReadPreventsDeliveryAndReleasesAdmission() {
        var auth = mock(AuthService.class);
        var actor = mock(AuthPrincipal.class);
        var workspace = WorkspaceId.random();
        var denied = new IllegalStateException("revoked during catalog read");
        var repository = mock(RepositoryBlobReader.class);
        var expected = new RepositoryMediaSnapshot(workspace, "a".repeat(40), RepositoryMediaIndex.empty(), Set.of());
        when(repository.selectCommit(workspace, Optional.empty())).thenReturn(Optional.of(expected.commit()));
        when(repository.media(workspace, expected.commit())).thenAnswer(call -> {
            when(auth.authorize(actor, workspace, Capability.READ_PRIVATE)).thenThrow(denied);
            return expected;
        });
        var service = new MediaFileService(auth, repository, mock(PublicContentSnapshots.class), () -> {
            throw new AssertionError("Listing must not open original storage");
        });
        assertThatThrownBy(() -> service.privateCatalog(actor, workspace, Optional.empty()))
                .isSameAs(denied);
        reset(auth);
        doReturn(expected).when(repository).media(workspace, expected.commit());
        for (int i = 0; i < 5; i++) {
            assertThat(service.privateCatalog(actor, workspace, Optional.empty()))
                    .isSameAs(expected);
        }
    }
}
