package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.ContentRepositoryException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Real Git writes with failed journal publication; external JVM-loss acceptance is a separate fixture. */
class NativeMoveCheckpointTests extends NativePlanFixture {
    private final RetainedCopyException failure = new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE);

    @Test
    void failedCandidatePublicationPreventsSubmissionAndRequiresExplicitAcceptanceAfterRecovery() {
        prepare();
        checkpoints(next -> next.nativePlan().moveState().attempt() != null);
        assertThatThrownBy(this::accept).isSameAs(failure);
        assertThat(repository.pushes()).isZero();
        assertThat(state.get().nativePlan().moveState().started()).isTrue();
        assertThat(state.get().nativePlan().moveState().attempt()).isNull();
        assertThat(state.get().baseCommit()).isEqualTo(repository.sourceCommit());
        reopen();
        assertThat(recover().path("code").stringValue()).isEqualTo("MOVE_NOT_SUBMITTED");
        assertThat(repository.pushes()).isZero();
        assertThat(state.get().nativePlan().localComplete()).isTrue();
        assertThat(accept().path("ok").booleanValue()).isTrue();
        assertThat(repository.pushes()).isEqualTo(1);
    }

    @Test
    void failedRemoteResultPublicationRecoversTheDurableCandidateWithoutAnotherPush() {
        prepare();
        checkpoints(next -> next.nativePlan().moveState().result() != null);
        assertThatThrownBy(this::accept).isSameAs(failure);
        NativeMoveState outcome = state.get().nativePlan().moveState();
        assertThat(outcome.result()).isNull();
        assertThat(outcome.attempt()).isNotNull();
        assertThat(repository.pushes()).isEqualTo(1);
        reopen();
        assertThat(recover().path("ok").booleanValue()).isTrue();
        NativeMoveState recovered = state.get().nativePlan().moveState();
        assertThat(recovered.attempt().object())
                .containsExactly(outcome.attempt().object());
        assertThat(recovered.result().commit()).isEqualTo(outcome.attempt().commit());
        assertThat(state.get().baseCommit()).isEqualTo(recovered.result().commit());
        assertThat(repository.pushes()).isEqualTo(1);
    }

    @Test
    void failedBaselinePublicationKeepsTheConfirmedPlanAndLaterLocalEdits() {
        prepare();
        Map<String, RetainedFileBaseline> previous = state.get().fileBaselines();
        checkpoints(next -> !next.nativePlan().moveState().baselined().isEmpty());
        assertThatThrownBy(this::accept).isSameAs(failure);
        assertThat(state.get().nativePlan().moveState().result()).isNotNull();
        assertThat(state.get().nativePlan().moveState().baselined()).isEmpty();
        assertThat(state.get().fileBaselines()).isEqualTo(previous);
        assertThat(state.get().baseCommit()).isEqualTo(repository.sourceCommit());
        byte[] edited = "edited after acceptance".getBytes(StandardCharsets.UTF_8);
        local.put("private/moved.md", edited);
        reopen();
        assertThat(recover().path("ok").booleanValue()).isTrue();
        assertThat(state.get().fileBaselines().get("private/moved.md").source()).isEqualTo("current-secret-needle");
        assertThat(local.get("private/moved.md")).containsExactly(edited);
        assertThat(repository.pushes()).isEqualTo(1);
    }

    @Test
    void failedPlanReleaseRetainsOriginalsAndConfirmedBaselines() {
        prepare();
        assertThat(accept().path("ok").booleanValue()).isTrue();
        RetainedSaveState confirmed = state.get();
        NativeRepositoryPlan plan = confirmed.nativePlan();
        String original = plan.files().get("private/secret.md").original().sha256();
        byte[] originalBytes = retained.get(original).clone();
        checkpoints(next -> next.nativePlan() == null);
        assertThatThrownBy(this::release).isSameAs(failure);
        assertThat(state.get()).isEqualTo(confirmed);
        assertThat(retained.get(original)).containsExactly(originalBytes);
        reopen();
        assertThat(release().path("ok").booleanValue()).isTrue();
        assertThat(state.get().nativePlan()).isNull();
        assertThat(retained).isEmpty();
        assertThat(state.get().fileBaselines()).isEqualTo(confirmed.fileBaselines());
        assertThat(state.get().baseCommit()).isEqualTo(confirmed.baseCommit());
        assertThat(repository.pushes()).isEqualTo(1);
    }

    @Test
    void failedHistoricalBaselineReadKeepsTheAcknowledgedCommitForLaterRecovery() {
        AuthorizedRepositoryReader delegate = saves.repository();
        AuthorizedRepositoryReader reader = mock(AuthorizedRepositoryReader.class);
        var unavailable = new AtomicBoolean(true);
        when(reader.getFile(any(), any(), any(), any())).thenAnswer(call -> {
            Optional<String> commit = call.getArgument(2);
            if (unavailable.get() && !commit.equals(Optional.of(repository.sourceCommit()))) {
                throw new ContentRepositoryException("Acknowledged objects temporarily unavailable");
            }
            return delegate.getFile(call.getArgument(0), call.getArgument(1), commit, call.getArgument(3));
        });
        saves = new SelectedFileSaves(auth, reader, repository.patches(auth), repository.moves(auth));
        host = new NativeRepositoryHost(
                saves,
                files,
                mock(NativeArtifactCommands.class),
                mock(NativeMediaCommands.class),
                new NativePlanCommands(files, saves),
                mock(NativeExportCommands.class));
        prepare();
        assertThat(accept().path("code").stringValue()).isEqualTo("REPOSITORY_UNAVAILABLE");
        assertThat(state.get().nativePlan().moveState().result()).isNotNull();
        assertThat(state.get().nativePlan().moveState().baselined()).isEmpty();
        assertThat(repository.pushes()).isEqualTo(1);
        unavailable.set(false);
        reopen();
        assertThat(recover().path("ok").booleanValue()).isTrue();
        assertThat(state.get().nativePlan().complete()).isTrue();
        assertThat(repository.pushes()).isEqualTo(1);
    }

    private void checkpoints(Predicate<RetainedSaveState> rejected) {
        when(held.state())
                .thenAnswer(call -> SelectedFileSaves.State.restore(state.get(), next -> {
                    if (rejected.test(next)) {
                        throw failure;
                    }
                    state.set(next);
                    journals.add(next);
                    if (next.nativePlan() == null) {
                        retained.clear();
                    }
                }));
    }

    private void reopen() {
        state.set(SandcrabProtocol.read(SandcrabProtocol.write(state.get()), RetainedSaveState.class));
        checkpoints(next -> false);
    }

    private void prepare() {
        assertThat(call("move_prepare", new BridgeArguments.Move("private/secret.md", "private/moved.md"))
                        .path("ok")
                        .booleanValue())
                .isTrue();
        state.get().nativePlan().files().forEach((path, file) -> {
            if (file.candidate() == null) {
                local.remove(path);
            } else {
                local.put(path, retained.get(file.candidate().sha256()).clone());
            }
        });
    }

    private JsonNode accept() {
        NativeRepositoryPlan plan = state.get().nativePlan();
        return call(
                "move_accept",
                new BridgeArguments.PlanAccept(
                        plan.id().toString(),
                        plan.paths().stream()
                                .map(path -> new BridgeArguments.PlanExpected(
                                        path, plan.files().get(path).candidateIdentity()))
                                .toList()));
    }

    private JsonNode recover() {
        return call(
                "move_recover",
                new BridgeArguments.PlanIdentity(state.get().nativePlan().id().toString()));
    }

    private JsonNode release() {
        return call(
                "plan_release",
                new BridgeArguments.PlanIdentity(state.get().nativePlan().id().toString()));
    }
}
