package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelectedFileSavesTests {
    @TempDir
    Path root;

    @Test
    void selectedSavesAdvanceOnlyHostBaselineAndRetainItOnARealRemoteConflict() throws Exception {
        var auth = mock(AuthService.class);
        var actor = actor();
        var workspace = WorkspaceId.random();
        doAnswer(call -> ((Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        var fixture = new PublicExecutionNativeFixture(root, root.resolve("exports"), auth, workspace);
        var reader = fixture.reader(auth);
        var saves = new SelectedFileSaves(auth, reader, fixture.patches(auth));
        var state = new SelectedFileSaves.State(fixture.sourceCommit());
        var result = saves.save(
                actor, workspace, state, Map.of("private/secret.md", "first\r\n", "private/new.md", "new"), List.of());
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(state.baseCommit).isNotEqualTo(fixture.sourceCommit());
        assertThat(reader.getFile(actor, workspace, Optional.empty(), "AGENTS.md")
                        .source())
                .contains("operator-secret-needle");
        assertThat(saves.save(actor, workspace, state, Map.of("private/secret.md", "second"), List.of("private/new.md"))
                        .get("ok"))
                .isEqualTo(true);
        assertThat(reader.getFile(actor, workspace, Optional.empty(), "private/new.md")
                        .expectedAbsence())
                .isTrue();
        String acknowledged = state.baseCommit;
        fixture.competingWrite(auth, actor);
        assertThat(saves.save(actor, workspace, state, Map.of("private/secret.md", "conflict"), List.of())
                        .get("code"))
                .isEqualTo("REPOSITORY_CONFLICT");
        assertThat(state.baseCommit).isEqualTo(acknowledged);
        assertThat(reader.getFile(actor, workspace, Optional.empty(), "private/secret.md")
                        .source())
                .contains("second");
    }

    @Test
    void unknownAcknowledgementPreventsAnotherWriteAndRetainsItsReceipt() throws Exception {
        var auth = mock(AuthService.class);
        var actor = actor();
        var workspace = WorkspaceId.random();
        var fixture = new PublicExecutionNativeFixture(root, root.resolve("exports"), auth, workspace);
        var patches = mock(RepositoryPatchService.class);
        when(patches.apply(any(), any(), any())).thenThrow(new RepositoryWriteAmbiguousException("offline"));
        var saves = new SelectedFileSaves(auth, fixture.reader(auth), patches);
        var state = new SelectedFileSaves.State(fixture.sourceCommit());
        assertThat(saves.save(actor, workspace, state, Map.of("private/secret.md", "uncertain"), List.of())
                        .get("code"))
                .isEqualTo("WRITE_OUTCOME_UNKNOWN");
        assertThat(saves.save(actor, workspace, state, Map.of("private/secret.md", "retry"), List.of())
                        .get("code"))
                .isEqualTo("WRITE_OUTCOME_UNKNOWN");
        verify(patches, times(1)).apply(any(), any(), any());
        assertThat(state.baseCommit).isEqualTo(fixture.sourceCommit());
        assertThat(state.lastSave.get("code")).isEqualTo("WRITE_OUTCOME_UNKNOWN");
    }

    private static AuthPrincipal actor() {
        var actor = mock(AuthPrincipal.class);
        when(actor.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(actor.subjectId()).thenReturn(UUID.randomUUID());
        return actor;
    }
}
