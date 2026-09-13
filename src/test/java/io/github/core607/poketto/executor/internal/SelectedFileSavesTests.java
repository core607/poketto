package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelectedFileSavesTests {
    @Test
    void publisherSavesPublicTextWithoutPrivateWriteAndCannotUseItForPrivatePaths() throws Exception {
        var auth = mock(AuthService.class);
        var actor = actor();
        var workspace = WorkspaceId.random();
        var allowed = Set.of(Capability.READ_PRIVATE, Capability.PUBLISH);
        doAnswer(call -> {
                    Capability[] required = (Capability[]) call.getRawArguments()[2];
                    if (!allowed.containsAll(List.of(required))) {
                        throw new AuthException(AuthException.Code.DENIED);
                    }
                    return new WorkspaceAccess(workspace, actor, MembershipRole.MEMBER, allowed);
                })
                .when(auth)
                .authorize(eq(actor), eq(workspace), any(Capability[].class));
        doAnswer(call -> {
                    Set<?> required = call.getArgument(2);
                    if (!allowed.containsAll(required)) {
                        throw new AuthException(AuthException.Code.DENIED);
                    }
                    return ((Supplier<?>) call.getArgument(3)).get();
                })
                .when(auth)
                .withAuthorization(eq(actor), eq(workspace), anySet(), any());
        var fixture = new PublicExecutionNativeFixture(root, root.resolve("exports"), auth, workspace);
        var reader = fixture.reader(auth);
        var saves = new SelectedFileSaves(auth, reader, fixture.patches(auth), fixture.moves(auth));
        var state = new SelectedFileSaves.State(fixture.sourceCommit());
        assertThat(saves.save(actor, workspace, state, Map.of("public/article.md", "# Public edit\n"), List.of())
                        .ok())
                .isEqualTo(true);
        assertThat(reader.getFile(actor, workspace, Optional.empty(), "public/article.md")
                        .source())
                .contains("# Public edit\n");
        String committed = state.baseCommit;
        assertThatThrownBy(() -> saves.save(actor, workspace, state, Map.of("private/secret.md", "denied"), List.of()))
                .isInstanceOf(AuthException.class);
        assertThat(state.baseCommit).isEqualTo(committed);
        assertThat(reader.getFile(actor, workspace, Optional.empty(), "private/secret.md")
                        .source())
                .contains("current-secret-needle");
    }

    @TempDir
    Path root;

    @Test
    void selectedSavesAdvanceOnlyHostBaselineAndRetainItOnARealRemoteConflict() throws Exception {
        var auth = mock(AuthService.class);
        when(auth.authorize(any(), any()))
                .thenAnswer(call -> new WorkspaceAccess(
                        call.getArgument(1),
                        call.getArgument(0),
                        MembershipRole.OWNER,
                        EnumSet.allOf(Capability.class)));
        var actor = actor();
        var workspace = WorkspaceId.random();
        doAnswer(call -> ((Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        var fixture = new PublicExecutionNativeFixture(root, root.resolve("exports"), auth, workspace);
        var reader = fixture.reader(auth);
        var saves = new SelectedFileSaves(auth, reader, fixture.patches(auth), fixture.moves(auth));
        var state = new SelectedFileSaves.State(fixture.sourceCommit());
        var result = saves.save(
                actor, workspace, state, Map.of("private/secret.md", "first\r\n", "private/new.md", "new"), List.of());
        assertThat(result.ok()).isEqualTo(true);
        assertThat(state.baseCommit).isNotEqualTo(fixture.sourceCommit());
        assertThat(reader.getFile(actor, workspace, Optional.empty(), "AGENTS.md")
                        .source())
                .contains("operator-secret-needle");
        assertThat(saves.save(actor, workspace, state, Map.of("private/secret.md", "second"), List.of("private/new.md"))
                        .ok())
                .isEqualTo(true);
        assertThat(reader.getFile(actor, workspace, Optional.empty(), "private/new.md")
                        .expectedAbsence())
                .isTrue();
        String acknowledged = state.baseCommit;
        fixture.competingWrite(auth, actor);
        assertThat(saves.save(actor, workspace, state, Map.of("private/secret.md", "conflict"), List.of())
                        .code())
                .isEqualTo("REPOSITORY_CONFLICT");
        assertThat(state.baseCommit).isEqualTo(acknowledged);
        assertThat(reader.getFile(actor, workspace, Optional.empty(), "private/secret.md")
                        .source())
                .contains("second");
    }

    @Test
    void unknownAcknowledgementPreventsAnotherWriteAndRetainsItsReceipt() throws Exception {
        var auth = mock(AuthService.class);
        doAnswer(call -> ((Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        when(auth.authorize(any(), any()))
                .thenAnswer(call -> new WorkspaceAccess(
                        call.getArgument(1),
                        call.getArgument(0),
                        MembershipRole.OWNER,
                        EnumSet.allOf(Capability.class)));
        var actor = actor();
        var workspace = WorkspaceId.random();
        var fixture = new PublicExecutionNativeFixture(root, root.resolve("exports"), auth, workspace);
        var patches = mock(RepositoryPatchService.class);
        when(patches.apply(any(), any(), any())).thenThrow(new RepositoryWriteAmbiguousException("offline"));
        var saves = new SelectedFileSaves(auth, fixture.reader(auth), patches, fixture.moves(auth));
        var state = new SelectedFileSaves.State(fixture.sourceCommit());
        assertThat(saves.save(actor, workspace, state, Map.of("private/secret.md", "uncertain"), List.of())
                        .code())
                .isEqualTo("WRITE_OUTCOME_UNKNOWN");
        assertThat(saves.save(actor, workspace, state, Map.of("private/secret.md", "retry"), List.of())
                        .code())
                .isEqualTo("WRITE_OUTCOME_UNKNOWN");
        verify(patches, times(1)).apply(any(), any(), any());
        assertThat(state.baseCommit).isEqualTo(fixture.sourceCommit());
        assertThat(((BridgeReplies.Reply) state.lastSave).code()).isEqualTo("WRITE_OUTCOME_UNKNOWN");
    }

    private static AuthPrincipal actor() {
        var actor = mock(AuthPrincipal.class);
        when(actor.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(actor.subjectId()).thenReturn(UUID.randomUUID());
        return actor;
    }

    @Test
    void synchronizingOnePathNeverAcceptsNewRemoteRevisionsForUnselectedLocalFiles() throws Exception {
        var auth = mock(AuthService.class);
        when(auth.authorize(any(), any()))
                .thenAnswer(call -> new WorkspaceAccess(
                        call.getArgument(1),
                        call.getArgument(0),
                        MembershipRole.OWNER,
                        EnumSet.allOf(Capability.class)));
        var actor = actor();
        var workspace = WorkspaceId.random();
        doAnswer(call -> ((Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        var fixture = new PublicExecutionNativeFixture(root, root.resolve("exports"), auth, workspace);
        var reader = fixture.reader(auth);
        var saves = new SelectedFileSaves(auth, reader, fixture.patches(auth), fixture.moves(auth));
        var state = new SelectedFileSaves.State(fixture.sourceCommit());
        fixture.competingWrite(auth, actor);
        var plan = saves.prepareSync(actor, workspace, state, "private/secret.md", Optional.of("local secret edit"));
        assertThat(state.baseCommit).isEqualTo(fixture.sourceCommit());
        assertThat(plan.content()).contains("local secret edit");
        assertThat(plan.conflicted()).isFalse();
        saves.acknowledgeSync(state, plan);
        assertThat(saves.save(actor, workspace, state, Map.of("private/secret.md", "local secret edit"), List.of())
                        .ok())
                .isEqualTo(true);
        assertThat(saves.save(actor, workspace, state, Map.of("AGENTS.md", "old local guide edit"), List.of())
                        .code())
                .isEqualTo("REPOSITORY_CONFLICT");
        assertThat(reader.getFile(actor, workspace, Optional.empty(), "AGENTS.md")
                        .source())
                .contains("externally-updated-guide");
        var guide = saves.prepareSync(actor, workspace, state, "AGENTS.md", Optional.of("old local guide edit"));
        assertThat(guide.conflicted()).isTrue();
        assertThat(guide.content().orElseThrow())
                .contains("operator-secret-needle", "old local guide edit", "externally-updated-guide");
    }

    @Test
    void recoveryAcknowledgesTheOriginalSaveAndAllowsLaterLocalEditsToBeSavedSeparately() throws Exception {
        var auth = mock(AuthService.class);
        when(auth.authorize(any(), any()))
                .thenAnswer(call -> new WorkspaceAccess(
                        call.getArgument(1),
                        call.getArgument(0),
                        MembershipRole.OWNER,
                        EnumSet.allOf(Capability.class)));
        var actor = actor();
        var workspace = WorkspaceId.random();
        doAnswer(call -> ((Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        var fixture = new PublicExecutionNativeFixture(root, root.resolve("exports"), auth, workspace, true);
        var reader = fixture.reader(auth);
        var saves = new SelectedFileSaves(auth, reader, fixture.patches(auth), fixture.moves(auth));
        var state = new SelectedFileSaves.State(fixture.sourceCommit());
        assertThat(saves.save(actor, workspace, state, Map.of("private/secret.md", "original-attempt"), List.of())
                        .code())
                .isEqualTo("WRITE_OUTCOME_UNKNOWN");
        String retained = state.attempt.orElseThrow().commit();
        assertThat(saves.save(actor, workspace, state, Map.of("private/secret.md", "later-edit"), List.of())
                        .code())
                .isEqualTo("WRITE_OUTCOME_UNKNOWN");
        fixture.restoreTransport();
        assertThat(saves.recover(actor, workspace, state).ok()).isEqualTo(true);
        assertThat(state.baseCommit).isEqualTo(retained);
        assertThat(fixture.pushes()).isEqualTo(1);
        assertThat(reader.getFile(actor, workspace, Optional.empty(), "private/secret.md")
                        .source())
                .contains("original-attempt");
        assertThat(saves.save(actor, workspace, state, Map.of("private/secret.md", "later-edit"), List.of())
                        .ok())
                .isEqualTo(true);
        assertThat(fixture.pushes()).isEqualTo(2);
        assertThat(reader.getFile(actor, workspace, Optional.empty(), "private/secret.md")
                        .source())
                .contains("later-edit");
    }
}
