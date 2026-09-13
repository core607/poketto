package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class RetainedSessionMovesTests {
    @TempDir
    Path root;

    private final AuthService auth = mock(AuthService.class);
    private final AuthPrincipal actor = mock(AuthPrincipal.class);
    private final WorkspaceId workspace = WorkspaceId.random();

    @Test
    void intentCandidateRemoteOutcomeAndLocalCompletionHaveSeparateCheckpoints() throws Exception {
        PublicExecutionNativeFixture fixture = fixture();
        var retained = new ArrayList<RetainedSaveState>();
        var pushes = new ArrayList<Integer>();
        var state = new SelectedFileSaves.State(fixture.sourceCommit(), snapshot -> {
            retained.add(snapshot);
            pushes.add(fixture.pushes());
        });
        SessionMoves moves = moves(fixture);
        SessionMoves.Pending plan =
                moves.prepare(actor, workspace, state, "private/secret.md", "private/moved.md", Optional.empty());
        byte[] original = plan.payload.clone();
        assertThat(moves.commit(actor, workspace, state, plan).code()).isEqualTo("LOCAL_MOVE_PENDING");
        assertThat(pushes).containsExactly(0, 0, 1);
        assertThat(retained.getFirst().move().payload()).containsExactly(original);
        assertThat(retained.get(1).move().attempt().commit()).isEqualTo(state.move.result.commit());
        assertThat(retained.get(2).move().result()).isNotNull();
        assertThat(retained.get(2).move().fileBaselines())
                .containsEntry(
                        "private/moved.md",
                        new RetainedFileBaseline(state.move.result.commit(), "current-secret-needle"))
                .containsEntry("private/secret.md", new RetainedFileBaseline(state.move.result.commit(), null));
        assertThat(plan.result).isNull();
        assertThat(plan.attempt).isNull();
        String committed = state.move.result.commit();
        assertThat(moves.installed(state).ok()).isTrue();
        assertThat(pushes).containsExactly(0, 0, 1, 1);
        assertThat(retained.getLast().move()).isNull();
        assertThat(state.baseline("private/moved.md")).isEqualTo(committed);
        assertThat(state.baseline("AGENTS.md")).isEqualTo(fixture.sourceCommit());
        var json = JsonMapper.builder().build();
        var snapshot = json.readValue(json.writeValueAsBytes(state.snapshot()), RetainedSaveState.class);
        var reopened = SelectedFileSaves.State.restore(snapshot, value -> {});
        var unavailable = mock(AuthorizedRepositoryReader.class);
        when(unavailable.getFile(any(), any(), any(), any()))
                .thenThrow(new ContentRepositoryException("historical objects unavailable"));
        var saves = new SelectedFileSaves(auth, unavailable, fixture.patches(auth), fixture.moves(auth));
        assertThat(saves.save(actor, workspace, reopened, Map.of("private/moved.md", "after reopen"), List.of())
                        .ok())
                .isTrue();
    }

    @Test
    void failedCandidateCheckpointPreventsRemoteMoveAndCanClearItsUnstartedIntent() throws Exception {
        PublicExecutionNativeFixture fixture = fixture();
        var calls = new AtomicInteger();
        var failure = new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE);
        var state = new SelectedFileSaves.State(fixture.sourceCommit(), snapshot -> {
            if (calls.incrementAndGet() == 2) {
                assertThat(snapshot.move().attempt()).isNotNull();
                throw failure;
            }
        });
        SessionMoves moves = moves(fixture);
        SessionMoves.Pending plan =
                moves.prepare(actor, workspace, state, "private/secret.md", "private/moved.md", Optional.empty());
        assertThatThrownBy(() -> moves.commit(actor, workspace, state, plan)).isSameAs(failure);
        assertThat(fixture.pushes()).isZero();
        assertThat(state.move.attempt).isNull();
        assertThat(moves.recover(actor, workspace, state).ok()).isTrue();
        assertThat(state.move).isNull();
        assertThat(fixture.pushes()).isZero();
    }

    @Test
    void lostCompletionCheckpointRetainsCandidateAndReopensWithoutSecondRemoteMove() throws Exception {
        PublicExecutionNativeFixture fixture = fixture();
        var retained = new ArrayList<RetainedSaveState>();
        var calls = new AtomicInteger();
        var failure = new AuthException(AuthException.Code.DENIED);
        var state = new SelectedFileSaves.State(fixture.sourceCommit(), snapshot -> {
            if (calls.incrementAndGet() == 3) {
                assertThat(snapshot.move().result()).isNotNull();
                throw failure;
            }
            retained.add(snapshot);
        });
        SessionMoves moves = moves(fixture);
        SessionMoves.Pending plan =
                moves.prepare(actor, workspace, state, "private/secret.md", "private/moved.md", Optional.empty());
        assertThatThrownBy(() -> moves.commit(actor, workspace, state, plan)).isSameAs(failure);
        assertThat(fixture.pushes()).isEqualTo(1);
        assertThat(state.move.result).isNull();
        assertThat(state.move.attempt).isNotNull();
        SelectedFileSaves.State reopened = SelectedFileSaves.State.restore(retained.getLast(), retained::add);
        assertThat(moves.recover(actor, workspace, reopened).code()).isEqualTo("LOCAL_MOVE_PENDING");
        assertThat(reopened.move.payload).containsExactly(plan.payload);
        assertThat(reopened.move.result.commit()).isEqualTo(reopened.move.attempt.commit());
        assertThat(fixture.pushes()).isEqualTo(1);
    }

    @Test
    void failedInstalledAndSkippedCheckpointsKeepTheConfirmedPlanAndOldLiveBaselines() throws Exception {
        PublicExecutionNativeFixture fixture = fixture();
        var fail = new AtomicInteger();
        var failure = new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE);
        var state = new SelectedFileSaves.State(fixture.sourceCommit(), snapshot -> {
            if (fail.get() == 1) {
                assertThat(snapshot.move()).isNull();
                throw failure;
            }
        });
        SessionMoves moves = moves(fixture);
        SessionMoves.Pending plan =
                moves.prepare(actor, workspace, state, "private/secret.md", "private/moved.md", Optional.empty());
        moves.commit(actor, workspace, state, plan);
        String committed = state.move.result.commit();
        fail.set(1);
        assertThatThrownBy(() -> moves.installed(state)).isSameAs(failure);
        assertThatThrownBy(() -> moves.skipLocal(state)).isSameAs(failure);
        assertThat(state.move.result.commit()).isEqualTo(committed);
        assertThat(state.baseCommit).isEqualTo(fixture.sourceCommit());
        fail.set(0);
        assertThat(moves.skipLocal(state).ok()).isTrue();
        assertThat(state.move).isNull();
        assertThat(state.baseCommit).isEqualTo(committed);
        assertThat(state.baseline("private/moved.md")).isEqualTo(fixture.sourceCommit());
        assertThat(fixture.pushes()).isEqualTo(1);
    }

    @Test
    void failedBaselineReadAfterPushKeepsTheCandidateForRecoveryWithoutDuplicateMove() throws Exception {
        PublicExecutionNativeFixture fixture = fixture();
        var unavailable = new AtomicBoolean(true);
        var reader = mock(AuthorizedRepositoryReader.class);
        var delegate = fixture.reader(auth);
        when(reader.getFile(any(), any(), any(), any())).thenAnswer(call -> {
            Optional<String> commit = call.getArgument(2);
            if (unavailable.get() && !commit.equals(Optional.of(fixture.sourceCommit()))) {
                throw new ContentRepositoryException("acknowledged objects temporarily unavailable");
            }
            return delegate.getFile(call.getArgument(0), call.getArgument(1), commit, call.getArgument(3));
        });
        var state = new SelectedFileSaves.State(fixture.sourceCommit(), value -> {});
        var moves = new SelectedFileSaves(auth, reader, fixture.patches(auth), fixture.moves(auth)).moves();
        var plan = moves.prepare(actor, workspace, state, "private/secret.md", "private/moved.md", Optional.empty());
        assertThatThrownBy(() -> moves.commit(actor, workspace, state, plan))
                .isInstanceOf(ContentRepositoryException.class)
                .hasMessageContaining("temporarily unavailable");
        assertThat(fixture.pushes()).isEqualTo(1);
        assertThat(state.move.attempt).isNotNull();
        assertThat(state.move.result).isNull();
        unavailable.set(false);
        assertThat(moves.recover(actor, workspace, state).code()).isEqualTo("LOCAL_MOVE_PENDING");
        assertThat(fixture.pushes()).isEqualTo(1);
        assertThat(state.move.fileBaselines.get("private/moved.md").source()).isEqualTo("current-secret-needle");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void moveRetainsRemoteIndexWithoutUnselectedLocalMediaMappings() throws Exception {
        PublicExecutionNativeFixture fixture = fixture();
        String base = fixture.seedMedia(auth, actor, new byte[] {1, 2}, new byte[] {3, 4});
        String source = fixture.reader(auth)
                .getFile(actor, workspace, Optional.of(base), RepositoryMediaIndex.PATH)
                .source()
                .orElseThrow();
        var original = RepositoryMediaIndex.parse(source.getBytes(StandardCharsets.UTF_8));
        var localFiles = new LinkedHashMap<>(original.files());
        localFiles.put("private/unsaved.pdf", localFiles.get("private/manual.pdf"));
        String local = new String(new RepositoryMediaIndex(localFiles).encode(), StandardCharsets.UTF_8);
        var state = new SelectedFileSaves.State(base, value -> {});
        SessionMoves moves = moves(fixture);
        var plan =
                moves.prepare(actor, workspace, state, "private/manual.pdf", "private/moved.pdf", Optional.of(local));
        assertThat(moves.commit(actor, workspace, state, plan).code()).isEqualTo("LOCAL_MOVE_PENDING");
        var transfer = JsonMapper.builder().build().readValue(plan.payload, SessionMoves.TransferredPlan.class);
        assertThat(RepositoryMediaIndex.parse(transfer.replacements().get(RepositoryMediaIndex.PATH))
                        .files())
                .containsKey("private/unsaved.pdf");
        assertThat(moves.installed(state).ok()).isTrue();
        String retained =
                state.snapshot().fileBaselines().get(RepositoryMediaIndex.PATH).source();
        assertThat(RepositoryMediaIndex.parse(retained.getBytes(StandardCharsets.UTF_8))
                        .files())
                .containsKey("private/moved.pdf")
                .doesNotContainKeys("private/manual.pdf", "private/unsaved.pdf");
    }

    private SessionMoves moves(PublicExecutionNativeFixture fixture) {
        return new SelectedFileSaves(auth, fixture.reader(auth), fixture.patches(auth), fixture.moves(auth)).moves();
    }

    private PublicExecutionNativeFixture fixture() throws Exception {
        when(actor.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(actor.subjectId()).thenReturn(UUID.randomUUID());
        when(auth.authorize(any(), any()))
                .thenAnswer(call -> new WorkspaceAccess(
                        call.getArgument(1),
                        call.getArgument(0),
                        MembershipRole.OWNER,
                        EnumSet.allOf(Capability.class)));
        doAnswer(call -> ((Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        return new PublicExecutionNativeFixture(root, root.resolve("exports"), auth, workspace);
    }
}
