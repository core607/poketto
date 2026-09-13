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
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetainedSelectedFileSavesTests {
    @TempDir
    Path root;

    private final AuthService auth = mock(AuthService.class);
    private final AuthPrincipal actor = mock(AuthPrincipal.class);
    private final WorkspaceId workspace = WorkspaceId.random();

    @Test
    void retainsIntentAndExactCandidateBeforePushAndCompletedBaselineBeforeReply() throws Exception {
        PublicExecutionNativeFixture fixture = fixture(false);
        var checkpoints = new ArrayList<RetainedSaveState>();
        var pushesAtCheckpoint = new ArrayList<Integer>();
        var state = new SelectedFileSaves.State(fixture.sourceCommit(), snapshot -> {
            checkpoints.add(snapshot);
            pushesAtCheckpoint.add(fixture.pushes());
        });
        var saved =
                saves(fixture).save(actor, workspace, state, Map.of("private/secret.md", "retained save"), List.of());

        assertThat(saved.ok()).isTrue();
        assertThat(pushesAtCheckpoint).containsExactly(0, 0, 1);
        assertThat(checkpoints.get(0).pending().changes().getFirst().content()).contains("retained save");
        assertThat(checkpoints.get(0).attempt()).isNull();
        assertThat(checkpoints.get(1).attempt().commit()).isEqualTo(state.baseCommit);
        assertThat(checkpoints.get(2).uncertain()).isFalse();
        assertThat(checkpoints.get(2).baseCommit()).isEqualTo(state.baseCommit);
        assertThat(checkpoints.get(2).baselines()).containsEntry("private/secret.md", state.baseCommit);
        assertThat(state.baseline("AGENTS.md")).isEqualTo(fixture.sourceCommit());
    }

    @Test
    void failedInitialPersistencePreventsWriteAndLeavesLiveStateUntouched() throws Exception {
        PublicExecutionNativeFixture fixture = fixture(false);
        var failure = new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE);
        var state = new SelectedFileSaves.State(fixture.sourceCommit(), snapshot -> {
            throw failure;
        });
        assertThatThrownBy(() -> saves(fixture)
                        .save(actor, workspace, state, Map.of("private/secret.md", "must not push"), List.of()))
                .isSameAs(failure);
        assertThat(fixture.pushes()).isZero();
        assertThat(state.uncertain).isFalse();
        assertThat(state.pending).isNull();
        assertThat(state.baseCommit).isEqualTo(fixture.sourceCommit());
    }

    @Test
    void failedCandidatePersistenceCannotPushAndUnstartedIntentCanBeClearedExplicitly() throws Exception {
        PublicExecutionNativeFixture fixture = fixture(false);
        var calls = new AtomicInteger();
        var failure = new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE);
        var state = new SelectedFileSaves.State(fixture.sourceCommit(), snapshot -> {
            if (calls.incrementAndGet() == 2) {
                assertThat(snapshot.attempt()).isNotNull();
                throw failure;
            }
        });
        SelectedFileSaves saves = saves(fixture);
        assertThatThrownBy(
                        () -> saves.save(actor, workspace, state, Map.of("private/secret.md", "prepared"), List.of()))
                .isSameAs(failure);
        assertThat(fixture.pushes()).isZero();
        assertThat(state.uncertain).isTrue();
        assertThat(state.attempt).isEmpty();
        assertThat(saves.recover(actor, workspace, state).ok()).isTrue();
        assertThat(state.uncertain).isFalse();
        assertThat(fixture.pushes()).isZero();
    }

    @Test
    void failedCompletionPersistenceRetainsCandidateAndRecoveryDoesNotPushTwice() throws Exception {
        PublicExecutionNativeFixture fixture = fixture(false);
        var calls = new AtomicInteger();
        var failure = new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE);
        var state = new SelectedFileSaves.State(fixture.sourceCommit(), snapshot -> {
            if (calls.incrementAndGet() == 3) {
                assertThat(fixture.pushes()).isEqualTo(1);
                assertThat(snapshot.uncertain()).isFalse();
                throw failure;
            }
        });
        SelectedFileSaves saves = saves(fixture);
        assertThatThrownBy(() ->
                        saves.save(actor, workspace, state, Map.of("private/secret.md", "remote saved"), List.of()))
                .isSameAs(failure);
        assertThat(fixture.reader(auth)
                        .getFile(actor, workspace, Optional.empty(), "private/secret.md")
                        .source())
                .contains("remote saved");
        assertThat(state.baseCommit).isEqualTo(fixture.sourceCommit());
        assertThat(state.uncertain).isTrue();
        String candidate = state.attempt.orElseThrow().commit();
        assertThat(saves.recover(actor, workspace, state).ok()).isTrue();
        assertThat(state.baseCommit).isEqualTo(candidate);
        assertThat(state.uncertain).isFalse();
        assertThat(fixture.pushes()).isEqualTo(1);
    }

    @Test
    void lostRemoteReplyKeepsThePersistedCandidateAndReconcilesAfterReopen() throws Exception {
        PublicExecutionNativeFixture fixture = fixture(true);
        var retained = new ArrayList<RetainedSaveState>();
        var state = new SelectedFileSaves.State(fixture.sourceCommit(), retained::add);
        SelectedFileSaves saves = saves(fixture);
        BridgeReplies.Reply unknown =
                saves.save(actor, workspace, state, Map.of("private/secret.md", "lost response"), List.of());
        assertThat(unknown.code()).isEqualTo("WRITE_OUTCOME_UNKNOWN");
        assertThat(retained.getLast().attempt().commit())
                .isEqualTo(retained.get(1).attempt().commit());
        fixture.restoreTransport();
        SelectedFileSaves.State reopened = SelectedFileSaves.State.restore(retained.getLast(), retained::add);
        assertThat(saves.recover(actor, workspace, reopened).ok()).isTrue();
        assertThat(reopened.uncertain).isFalse();
        assertThat(fixture.pushes()).isEqualTo(1);
    }

    @Test
    void failedSyncCheckpointDoesNotAdvanceTheLivePathBaseline() throws Exception {
        PublicExecutionNativeFixture fixture = fixture(false);
        fixture.competingWrite(auth, actor);
        var proposed = new ArrayList<RetainedSaveState>();
        var failure = new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE);
        var state = new SelectedFileSaves.State(fixture.sourceCommit(), snapshot -> {
            proposed.add(snapshot);
            throw failure;
        });
        SelectedFileSaves saves = saves(fixture);
        SelectedFileSaves.SyncPlan plan = saves.prepareSync(actor, workspace, state, "AGENTS.md", Optional.empty());
        assertThatThrownBy(() -> saves.acknowledgeSync(state, plan)).isSameAs(failure);
        assertThat(proposed.getFirst().baselines()).containsEntry("AGENTS.md", plan.remoteCommit());
        assertThat(state.baseCommit).isEqualTo(fixture.sourceCommit());
        assertThat(state.baseline("AGENTS.md")).isEqualTo(fixture.sourceCommit());
    }

    private SelectedFileSaves saves(PublicExecutionNativeFixture fixture) {
        return new SelectedFileSaves(auth, fixture.reader(auth), fixture.patches(auth), fixture.moves(auth));
    }

    private PublicExecutionNativeFixture fixture(boolean loseReply) throws Exception {
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
        return new PublicExecutionNativeFixture(root, root.resolve("exports"), auth, workspace, loseReply);
    }
}
