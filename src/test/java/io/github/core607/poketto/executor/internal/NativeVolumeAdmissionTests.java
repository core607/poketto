package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NativeVolumeAdmissionTests extends NativeRepositoryTestFixture {
    @Test
    void temporaryStorageContentionKeepsOnePlanAndReachesAUsableCopy() {
        List<SandcrabVolume.Plan> attempted = new ArrayList<>();
        doAnswer(call -> {
                    SandcrabVolume.Plan plan = call.getArgument(0);
                    attempted.add(plan);
                    if (attempted.size() < 3) {
                        throw new SandcrabClient.Rejected(429, "STORAGE_CAPACITY");
                    }
                    return created(plan);
                })
                .when(client)
                .createVolume(any());
        try (var executor = executor((run, timeout, handler) -> {})) {
            String copy = execute(executor, "new").copyId();
            assertThat(journal.record(owner).orElseThrow().phase()).isEqualTo(AccountCopyRecord.Phase.READY);
            assertThat(execute(executor, copy).exitCode()).isZero();
        }
        assertThat(attempted).hasSize(3).allMatch(attempted.getFirst()::equals);
        assertThat(volumes).hasSize(1);
    }

    @Test
    void cancellationAfterAnExplicitRefusalDoesNotPoisonTheNextNewCopy() {
        var cancelled = new AtomicBoolean();
        when(cancellation.isCancelled()).thenAnswer(call -> cancelled.get());
        doAnswer(call -> {
                    cancelled.set(true);
                    throw new SandcrabClient.Rejected(429, "STORAGE_CAPACITY");
                })
                .when(client)
                .createVolume(any());
        try (var executor = executor((run, timeout, handler) -> {})) {
            assertThatThrownBy(() -> execute(executor, "new")).isInstanceOf(ExecutionAdmissionException.class);
            assertThat(journal.record(owner)).isEmpty();
            assertThat(journal.removed()).isEqualTo(1);
            verify(client, times(1)).createVolume(any());
            verify(client, never()).open(any());
            cancelled.set(false);
            doAnswer(call -> created(call.getArgument(0))).when(client).createVolume(any());
            assertThat(execute(executor, "new").exitCode()).isZero();
        }
        verify(client, never()).deleteVolume(any());
    }

    @Test
    void unknownCreateReplyRetainsTheJournalAndNeverSubmitsAgain() {
        doAnswer(call -> {
                    created(call.getArgument(0));
                    throw new WorkerUnavailableException();
                })
                .when(client)
                .createVolume(any());
        try (var executor = executor((run, timeout, handler) -> {})) {
            assertThatThrownBy(() -> execute(executor, "new")).isInstanceOf(ExecutionAdmissionException.class);
            AccountCopyRecord retained = journal.record(owner).orElseThrow();
            assertThat(retained.phase()).isEqualTo(AccountCopyRecord.Phase.INITIALIZING);
            assertThatThrownBy(() -> execute(executor, retained.copyId().toString()))
                    .isInstanceOf(ExecutionAdmissionException.class);
            assertThat(journal.record(owner)).contains(retained);
        }
        assertThat(volumes).hasSize(1);
        verify(client, times(1)).createVolume(any());
        verify(client, never()).deleteVolume(any());
        assertThat(journal.removed()).isZero();
    }

    @Test
    void anUnknownRetryCannotInheritAnEarlierDefiniteRefusal() {
        var attempts = new AtomicInteger();
        doAnswer(call -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new SandcrabClient.Rejected(429, "STORAGE_CAPACITY");
                    }
                    created(call.getArgument(0));
                    throw new WorkerUnavailableException();
                })
                .when(client)
                .createVolume(any());
        try (var executor = executor((run, timeout, handler) -> {})) {
            assertThatThrownBy(() -> execute(executor, "new")).isInstanceOf(ExecutionAdmissionException.class);
            assertThat(journal.record(owner)).isPresent();
        }
        assertThat(attempts).hasValue(2);
        assertThat(journal.removed()).isZero();
        assertThat(volumes).hasSize(1);
        verify(client, never()).deleteVolume(any());
    }

    @Test
    void acceptedVolumeReadinessFailureRetainsItsRecoveryJournal() {
        doAnswer(call -> {
                    SandcrabVolume ready = created(call.getArgument(0));
                    return new SandcrabVolume(
                            ready.id(),
                            ready.profile(),
                            "creating",
                            null,
                            ready.byteLimit(),
                            ready.inodeLimit(),
                            null,
                            ready.planDigest(),
                            false,
                            null);
                })
                .when(client)
                .createVolume(any());
        doThrow(new WorkerUnavailableException()).when(client).volume(any(SandcrabVolume.class));
        try (var executor = executor((run, timeout, handler) -> {})) {
            assertThatThrownBy(() -> execute(executor, "new")).isInstanceOf(ExecutionAdmissionException.class);
            assertThat(journal.record(owner)).isPresent();
        }
        verify(client, times(1)).createVolume(any());
        verify(client, never()).deleteVolume(any());
        assertThat(journal.removed()).isZero();
        assertThat(volumes).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"VOLUME_CAPACITY", "ID_CONFLICT"})
    void otherRefusalsAreNotRetriedOrReclassifiedAsAnEmptyInitialization(String code) {
        doThrow(new SandcrabClient.Rejected(code.equals("ID_CONFLICT") ? 409 : 429, code))
                .when(client)
                .createVolume(any());
        try (var executor = executor((run, timeout, handler) -> {})) {
            assertThatThrownBy(() -> execute(executor, "new")).isInstanceOf(ExecutionAdmissionException.class);
            assertThat(journal.record(owner)).isPresent();
        }
        verify(client, times(1)).createVolume(any());
        assertThat(journal.removed()).isZero();
    }

    @Test
    void aLaterSessionRefusalCannotRemoveAnAlreadyAcceptedVolumeOrItsJournal() {
        doThrow(new SandcrabClient.Rejected(429, "SESSION_CAPACITY"))
                .when(client)
                .open(any());
        try (var executor = executor((run, timeout, handler) -> {})) {
            assertThatThrownBy(() -> execute(executor, "new")).isInstanceOf(ExecutionAdmissionException.class);
            assertThat(journal.record(owner)).isPresent();
        }
        assertThat(volumes).hasSize(1);
        assertThat(journal.removed()).isZero();
        verify(client, never()).deleteVolume(any());
    }

    @Test
    void exportReleaseFailureCannotMaskAnUncertainVolumeOutcome() {
        var uncertain = new WorkerUnavailableException();
        var cleanup = new IllegalStateException("Synthetic export release failure");
        doThrow(uncertain).when(client).createVolume(any());
        doThrow(cleanup).when(exports).release(any());
        try (var executor = executor((run, timeout, handler) -> {})) {
            assertThatThrownBy(() -> execute(executor, "new"))
                    .isInstanceOf(ExecutionAdmissionException.class)
                    .satisfies(failure -> assertThat(rootCause(failure)).isSameAs(uncertain));
            assertThat(uncertain.getSuppressed()).containsExactly(cleanup);
            assertThat(journal.record(owner)).isPresent();
        }
    }

    @Test
    void anUnreadableJournalAfterFailedCleanupKeepsTheOriginalFailure() {
        var cancelled = new AtomicBoolean();
        var cleanup = new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE);
        journal.failNextDiscard(cleanup);
        when(cancellation.isCancelled()).thenAnswer(call -> cancelled.get());
        doAnswer(call -> {
                    cancelled.set(true);
                    throw new SandcrabClient.Rejected(429, "STORAGE_CAPACITY");
                })
                .when(client)
                .createVolume(any());
        try (var executor = executor((run, timeout, handler) -> {})) {
            assertThatThrownBy(() -> execute(executor, "new"))
                    .isInstanceOf(ExecutionAdmissionException.class)
                    .satisfies(failure -> {
                        Throwable primary = rootCause(failure);
                        assertThat(primary).isInstanceOf(WorkerUnavailableException.class);
                        assertThat(primary.getSuppressed()).contains(cleanup);
                    });
            assertThat(journal.record(owner)).isPresent();
            assertThat(journal.removed()).isZero();
        }
        verify(client, never()).open(any());
        verify(client, never()).deleteVolume(any());
    }

    private SandcrabVolume created(SandcrabVolume.Plan plan) {
        var volume = new SandcrabVolume(
                plan.id(),
                plan.profile(),
                "ready",
                plan.cloneFrom(),
                plan.byteLimit(),
                plan.inodeLimit(),
                null,
                "c".repeat(64),
                false,
                null);
        volumes.put(volume.id(), volume);
        return volume;
    }

    private static Throwable rootCause(Throwable failure) {
        while (failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }
}
