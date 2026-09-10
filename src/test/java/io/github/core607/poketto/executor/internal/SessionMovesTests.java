package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class SessionMovesTests {
    @TempDir
    Path root;

    private final WorkspaceId workspace = WorkspaceId.random();
    private final AuthService auth = mock(AuthService.class);
    private final AuthPrincipal actor = mock(AuthPrincipal.class);

    private PublicExecutionNativeFixture fixture(boolean loseReply) throws Exception {
        when(actor.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(actor.subjectId()).thenReturn(UUID.randomUUID());
        doAnswer(call -> ((Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        return new PublicExecutionNativeFixture(root, root.resolve("exports"), auth, workspace, loseReply);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void indexMergeKeepsUnselectedDraftMappingsOutOfTheRemoteCommit() throws Exception {
        var fixture = fixture(false);
        String initial = fixture.seedMedia(auth, actor, new byte[] {1}, new byte[] {2});
        var reader = fixture.reader(auth);
        String index = reader.getFile(actor, workspace, Optional.of(initial), RepositoryMediaIndex.PATH)
                .source()
                .orElseThrow();
        var local = new LinkedHashMap<>(RepositoryMediaIndex.parse(index.getBytes(StandardCharsets.UTF_8))
                .files());
        var draft = new RepositoryMediaIndex.Media(UUID.randomUUID(), "d".repeat(64), "application/pdf", 3);
        local.put("private/unsaved.pdf", draft);
        String localText = new String(new RepositoryMediaIndex(local).encode(), StandardCharsets.UTF_8);
        var saves = new SelectedFileSaves(auth, reader, fixture.patches(auth), fixture.moves(auth));
        var state = new SelectedFileSaves.State(initial);
        var pending = saves.moves()
                .prepare(
                        actor,
                        workspace,
                        state,
                        "private/manual.pdf",
                        "private/archive/manual.pdf",
                        Optional.of(localText));
        var payload = new ObjectMapper().readTree(pending.payload);
        var merged = RepositoryMediaIndex.parse(Base64.getDecoder()
                .decode(payload.path("replacements")
                        .path(RepositoryMediaIndex.PATH)
                        .stringValue()));
        assertThat(merged.files())
                .containsEntry("private/unsaved.pdf", draft)
                .containsKey("private/archive/manual.pdf")
                .doesNotContainKey("private/manual.pdf");
        assertThat(saves.moves().commit(actor, workspace, state, pending).get("code"))
                .isEqualTo("LOCAL_MOVE_PENDING");
        assertThat(state.baseCommit).isEqualTo(initial);
        assertThat(saves.save(actor, workspace, state, Map.of("private/later.md", "later"), List.of())
                        .get("code"))
                .isEqualTo("RECOVER_MOVE_FIRST");
        var remote =
                RepositoryMediaIndex.parse(reader.getFile(actor, workspace, Optional.empty(), RepositoryMediaIndex.PATH)
                        .source()
                        .orElseThrow()
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(remote.files())
                .containsKey("private/archive/manual.pdf")
                .doesNotContainKeys("private/manual.pdf", "private/unsaved.pdf");
        String committed = pending.result.commit();
        saves.moves().installed(state);
        assertThat(state.move).isNull();
        assertThat(state.baseCommit).isEqualTo(committed);
        assertThat(state.baseline(RepositoryMediaIndex.PATH)).isEqualTo(committed);
        assertThat(state.baseline("private/secret.md")).isEqualTo(initial);
    }

    @Test
    void recoveryKeepsTheSamePayloadAndDoesNotReplayAnAcknowledgedMove() throws Exception {
        var fixture = fixture(true);
        var saves = new SelectedFileSaves(auth, fixture.reader(auth), fixture.patches(auth), fixture.moves(auth));
        var state = new SelectedFileSaves.State(fixture.sourceCommit());
        var pending = saves.moves()
                .prepare(actor, workspace, state, "private/secret.md", "private/moved.md", Optional.empty());
        byte[] original = pending.payload.clone();
        assertThat(saves.moves().commit(actor, workspace, state, pending).get("code"))
                .isEqualTo("WRITE_OUTCOME_UNKNOWN");
        assertThat(pending.attempt).isNotNull();
        assertThatThrownBy(() ->
                        saves.moves().prepare(actor, workspace, state, "article.md", "other.md", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        fixture.restoreTransport();
        assertThat(saves.moves().recover(actor, workspace, state).get("code")).isEqualTo("LOCAL_MOVE_PENDING");
        assertThat(pending.result.commit()).isEqualTo(pending.attempt.commit());
        assertThat(pending.payload).containsExactly(original);
        assertThat(fixture.pushes()).isEqualTo(1);
        saves.moves().recover(actor, workspace, state);
        assertThat(fixture.pushes()).isEqualTo(1);
        assertThat(state.move).isSameAs(pending);
        saves.moves().installed(state);
        assertThat(state.move).isNull();
        assertThat(state.baseCommit).isEqualTo(pending.result.commit());
    }
}
