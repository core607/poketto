package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryFile;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Non-text presence and revisions survive native move acceptance without later historical reads. */
class NativeNonTextMoveTests extends NativePlanFixture {
    @Test
    void binaryMoveRetainsBytesAndItsUnreadableBaselineAcrossJournalReopening() throws Exception {
        byte[] bytes = {0, 1, -1, 10};
        resetBaseline(repository.seedFile("private/source.bin", bytes));
        local.put("private/source.bin", bytes);
        complete("private/source.bin", "private/dest.bin");
        assertThat(local.get("private/dest.bin")).containsExactly(bytes);
        RepositoryFile restored = retained("private/dest.bin");
        assertThat(restored.expectedAbsence()).isFalse();
        assertThat(restored.source()).isEmpty();
        assertThat(restored.revision()).contains(DocumentRevision.sha256(bytes));
        assertThat(restored.diagnostics()).extracting(value -> value.code()).containsExactly("INVALID_UTF8");
        assertThat(retained("private/source.bin").expectedAbsence()).isTrue();
        assertThat(repository.pushes()).isEqualTo(1);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void anUnmaterializedManagedOriginalStaysPresentInTheRetainedDomainBaseline() throws Exception {
        resetBaseline(repository.seedMedia(auth, actor, new byte[] {1, 2}, new byte[] {3, 4}));
        int pushes = repository.pushes();
        assertThat(local).doesNotContainKey("private/manual.pdf");
        complete("private/manual.pdf", "private/moved.pdf");
        assertThat(local).doesNotContainKeys("private/manual.pdf", "private/moved.pdf");
        RepositoryFile restored = retained("private/moved.pdf");
        assertThat(restored.expectedAbsence()).isFalse();
        assertThat(restored.source()).isEmpty();
        assertThat(restored.revision()).isEmpty();
        assertThat(restored.diagnostics()).extracting(value -> value.code()).containsExactly("MANAGED_MEDIA");
        assertThat(repository.pushes()).isEqualTo(pushes + 1);
    }

    private void complete(String source, String destination) {
        assertThat(call("move_prepare", new BridgeArguments.Move(source, destination))
                        .path("ok")
                        .booleanValue())
                .isTrue();
        NativeRepositoryPlan plan = state.get().nativePlan();
        plan.files().forEach((path, file) -> {
            if (file.candidate() == null) {
                local.remove(path);
            } else {
                local.put(path, retained.get(file.candidate().sha256()).clone());
            }
        });
        List<BridgeArguments.PlanExpected> expected = plan.paths().stream()
                .map(path -> new BridgeArguments.PlanExpected(
                        path, plan.files().get(path).candidateIdentity()))
                .toList();
        assertThat(call("move_accept", new BridgeArguments.PlanAccept(plan.id().toString(), expected))
                        .path("ok")
                        .booleanValue())
                .isTrue();
        assertThat(call(
                                "plan_release",
                                new BridgeArguments.PlanIdentity(plan.id().toString()))
                        .path("ok")
                        .booleanValue())
                .isTrue();
    }

    private RepositoryFile retained(String path) {
        RetainedSaveState snapshot =
                SandcrabProtocol.read(SandcrabProtocol.write(state.get()), RetainedSaveState.class);
        AuthorizedRepositoryReader reader = mock(AuthorizedRepositoryReader.class);
        when(reader.getFile(any(), any(), any(), any()))
                .thenThrow(new ContentRepositoryException("Historical lookup is unavailable"));
        var restoredSaves = new SelectedFileSaves(auth, reader, repository.patches(auth), repository.moves(auth));
        SelectedFileSaves.State restored = SelectedFileSaves.State.restore(snapshot);
        RepositoryFile result = restoredSaves.baselineFile(actor, workspace, restored, path);
        RepositoryFile authoritative =
                repository.reader(auth).getFile(actor, workspace, Optional.of(snapshot.baseCommit()), path);
        assertThat(result).isEqualTo(authoritative);
        if (!result.expectedAbsence()) {
            assertThatThrownBy(() -> restoredSaves.save(actor, workspace, restored, Map.of(), List.of(path)))
                    .isInstanceOf(InvalidSelectionException.class)
                    .hasMessageContaining("NO_WRITABLE_BASELINE");
        }
        verifyNoInteractions(reader);
        return result;
    }
}
