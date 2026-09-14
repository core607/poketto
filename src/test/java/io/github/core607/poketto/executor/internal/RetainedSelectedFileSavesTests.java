package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

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
        var recoveryFailure = new RepositoryConflictException("checkpoint baseline unavailable");
        var state = new SelectedFileSaves.State(fixture.sourceCommit(), snapshot -> {
            int stage = calls.incrementAndGet();
            if (stage == 3) {
                assertThat(fixture.pushes()).isEqualTo(1);
                assertThat(snapshot.uncertain()).isFalse();
                throw failure;
            }
            if (stage == 4) {
                throw recoveryFailure;
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
        assertThatThrownBy(() -> saves.recover(actor, workspace, state)).isSameAs(recoveryFailure);
        assertThat(state.uncertain).isTrue();
        assertThat(state.attempt.orElseThrow().commit()).isEqualTo(candidate);
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
    void reopenedSaveUsesIndependentRetainedTextAndAbsenceWithoutHistoricalReads() throws Exception {
        PublicExecutionNativeFixture fixture = fixture(false);
        SelectedFileSaves original = saves(fixture);
        var state = new SelectedFileSaves.State(fixture.sourceCommit(), snapshot -> {});
        assertThat(original.save(actor, workspace, state, Map.of("private/secret.md", "猫咪的旧基线"), List.of())
                        .ok())
                .isTrue();
        String first = state.baseCommit;
        assertThat(original.save(actor, workspace, state, Map.of("AGENTS.md", "another file"), List.of())
                        .ok())
                .isTrue();
        SelectedFileSaves.State restored = reopen(state);
        SelectedFileSaves resumed = withoutHistoricalReads(fixture);
        var baseline = resumed.baselineFile(actor, workspace, restored, "private/secret.md");
        assertThat(baseline.commit()).contains(first);
        assertThat(baseline.source()).contains("猫咪的旧基线");
        assertThat(baseline.revision()).contains(DocumentRevision.sha256("猫咪的旧基线".getBytes(StandardCharsets.UTF_8)));
        assertThat(resumed.save(actor, workspace, restored, Map.of("private/secret.md", "next"), List.of())
                        .ok())
                .isTrue();
        assertThat(resumed.save(actor, workspace, restored, Map.of(), List.of("private/secret.md"))
                        .ok())
                .isTrue();
        restored = reopen(restored);
        assertThat(resumed.baselineFile(actor, workspace, restored, "private/secret.md")
                        .expectedAbsence())
                .isTrue();
        assertThat(resumed.save(actor, workspace, restored, Map.of("private/secret.md", "recreated"), List.of())
                        .ok())
                .isTrue();
        assertThat(fixture.reader(auth)
                        .getFile(actor, workspace, Optional.empty(), "private/secret.md")
                        .source())
                .contains("recreated");
    }

    @Test
    void retainedTextDoesNotBypassRevokedPrivateRead() throws Exception {
        PublicExecutionNativeFixture fixture = fixture(false);
        var state = new SelectedFileSaves.State(fixture.sourceCommit(), snapshot -> {});
        assertThat(saves(fixture)
                        .save(actor, workspace, state, Map.of("private/secret.md", "retained"), List.of())
                        .ok())
                .isTrue();
        SelectedFileSaves.State restored = reopen(state);
        SelectedFileSaves resumed = withoutHistoricalReads(fixture);
        doThrow(new AuthException(AuthException.Code.DENIED))
                .when(auth)
                .withAuthorization(eq(actor), eq(workspace), eq(Set.of(Capability.READ_PRIVATE)), any());
        assertThatThrownBy(() -> resumed.baselineFile(actor, workspace, restored, "private/secret.md"))
                .isInstanceOf(AuthException.class);
    }

    private static SelectedFileSaves.State reopen(SelectedFileSaves.State state) {
        var json = JsonMapper.builder().build();
        var stored = json.readValue(json.writeValueAsBytes(state.snapshot()), RetainedSaveState.class);
        return SelectedFileSaves.State.restore(stored, snapshot -> {});
    }

    private SelectedFileSaves withoutHistoricalReads(PublicExecutionNativeFixture fixture) {
        var reader = mock(AuthorizedRepositoryReader.class);
        var delegate = fixture.reader(auth);
        when(reader.getFile(any(), any(), any(), any())).thenAnswer(call -> {
            Optional<String> commit = call.getArgument(2);
            if (commit.isPresent()) {
                throw new ContentRepositoryException("historical objects unavailable in disposable cache");
            }
            return delegate.getFile(call.getArgument(0), call.getArgument(1), commit, call.getArgument(3));
        });
        return new SelectedFileSaves(auth, reader, fixture.patches(auth), fixture.moves(auth));
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
