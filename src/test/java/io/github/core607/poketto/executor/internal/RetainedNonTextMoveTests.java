package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class RetainedNonTextMoveTests {
    @TempDir
    Path root;

    private final AuthService auth = mock(AuthService.class);
    private final AuthPrincipal actor = mock(AuthPrincipal.class);
    private final WorkspaceId workspace = WorkspaceId.random();

    @BeforeEach
    void authorize() {
        when(actor.subjectId()).thenReturn(UUID.randomUUID());
        when(actor.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(auth.authorize(any(), any()))
                .thenAnswer(call -> new WorkspaceAccess(
                        call.getArgument(1),
                        call.getArgument(0),
                        MembershipRole.OWNER,
                        EnumSet.allOf(Capability.class)));
        doAnswer(call -> ((Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
    }

    @Test
    void binaryMoveKeepsItsRevisionAndPresenceThroughPendingAndInstalledRecovery() throws Exception {
        try (var fixture = fixture()) {
            byte[] bytes = new byte[] {0, 1, -1, 10};
            String base = fixture.seedFile("private/source.bin", bytes);
            assertThat(fixture.reader(auth)
                            .getFile(actor, workspace, Optional.of(base), "public/article.md")
                            .source())
                    .isPresent();
            var state = move(fixture, base, "private/source.bin", "private/dest.bin", Optional.empty());
            var pending = reopen(state);
            pending.snapshot().requireRecoverable();
            var expected = fixture.reader(auth)
                    .getFile(actor, workspace, Optional.of(pending.move.result.commit()), "private/dest.bin");
            assertThat(pending.move.fileBaselines.get("private/dest.bin").file(workspace, "private/dest.bin"))
                    .isEqualTo(expected);
            var saves = unavailable(fixture);
            assertThat(saves.moves().installed(pending).ok()).isTrue();
            var installed = reopen(pending);
            var restored = saves.baselineFile(actor, workspace, installed, "private/dest.bin");
            assertThat(restored).isEqualTo(expected);
            assertThat(restored.expectedAbsence()).isFalse();
            assertThat(restored.source()).isEmpty();
            assertThat(restored.revision()).contains(DocumentRevision.sha256(bytes));
            assertThat(restored.diagnostics()).extracting(value -> value.code()).containsExactly("INVALID_UTF8");
            assertThat(saves.baselineFile(actor, workspace, installed, "private/source.bin")
                            .expectedAbsence())
                    .isTrue();
            assertThatThrownBy(() -> saves.save(actor, workspace, installed, Map.of(), List.of("private/dest.bin")))
                    .isInstanceOf(InvalidSelectionException.class)
                    .hasMessageContaining("NO_WRITABLE_BASELINE");
            assertThat(installed.uncertain).isFalse();
            assertThat(fixture.pushes()).isEqualTo(1);
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void managedMoveKeepsUnversionedPresenceAndDiagnosticsWithoutHistoricalLookup() throws Exception {
        try (var fixture = fixture()) {
            String base = fixture.seedMedia(auth, actor, new byte[] {1, 2}, new byte[] {3, 4});
            var index = fixture.reader(auth).getFile(actor, workspace, Optional.of(base), RepositoryMediaIndex.PATH);
            var state = move(fixture, base, "private/manual.pdf", "private/moved.pdf", index.source());
            var pending = reopen(state);
            assertThat(pending.move.fileBaselines.keySet()).containsExactlyInAnyOrderElementsOf(pending.move.paths);
            var saves = unavailable(fixture);
            assertThat(saves.moves().installed(pending).ok()).isTrue();
            var installed = reopen(pending);
            var file = saves.baselineFile(actor, workspace, installed, "private/moved.pdf");
            assertThat(file.expectedAbsence()).isFalse();
            assertThat(file.source()).isEmpty();
            assertThat(file.revision()).isEmpty();
            assertThat(file.diagnostics()).extracting(value -> value.code()).containsExactly("MANAGED_MEDIA");
            assertThat(saves.baselineFile(actor, workspace, installed, "private/manual.pdf")
                            .expectedAbsence())
                    .isTrue();
            assertThatThrownBy(() ->
                            saves.save(actor, workspace, installed, Map.of("private/moved.pdf", "text"), List.of()))
                    .isInstanceOf(InvalidSelectionException.class)
                    .hasMessageContaining("NO_WRITABLE_BASELINE");
            assertThat(installed.uncertain).isFalse();
            assertThat(fixture.pushes()).isEqualTo(2);
        }
    }

    private SelectedFileSaves.State move(
            PublicExecutionNativeFixture fixture,
            String commit,
            String source,
            String destination,
            Optional<String> index) {
        var state = new SelectedFileSaves.State(commit, checkpoint -> {});
        var moves =
                new SelectedFileSaves(auth, fixture.reader(auth), fixture.patches(auth), fixture.moves(auth)).moves();
        var plan = moves.prepare(actor, workspace, state, source, destination, index);
        assertThat(moves.commit(actor, workspace, state, plan).code()).isEqualTo("LOCAL_MOVE_PENDING");
        return state;
    }

    private SelectedFileSaves.State reopen(SelectedFileSaves.State state) {
        var json = JsonMapper.builder().build();
        return SelectedFileSaves.State.restore(
                json.readValue(json.writeValueAsBytes(state.snapshot()), RetainedSaveState.class), value -> {});
    }

    private SelectedFileSaves unavailable(PublicExecutionNativeFixture fixture) {
        var reader = mock(AuthorizedRepositoryReader.class);
        when(reader.getFile(any(), any(), any(), any()))
                .thenThrow(new ContentRepositoryException("historical baseline unavailable"));
        return new SelectedFileSaves(auth, reader, fixture.patches(auth), fixture.moves(auth));
    }

    private PublicExecutionNativeFixture fixture() throws Exception {
        return new PublicExecutionNativeFixture(root, root.resolve("exports"), auth, workspace);
    }
}
