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
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        assertThat(plan.result).isNull();
        assertThat(plan.attempt).isNull();
        String committed = state.move.result.commit();
        assertThat(moves.installed(state).ok()).isTrue();
        assertThat(pushes).containsExactly(0, 0, 1, 1);
        assertThat(retained.getLast().move()).isNull();
        assertThat(state.baseline("private/moved.md")).isEqualTo(committed);
        assertThat(state.baseline("AGENTS.md")).isEqualTo(fixture.sourceCommit());
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
