package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.SiteGroup;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NativeRepositoryCopyClaimsTests extends NativeRepositoryTestFixture {
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void overlappingCallsForkWithinQuotaAndOtherwiseRefuseBeforeNativeAdmission(int copies) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        try (var executor = executor(copies, (run, timeout, handler) -> {
                    if (first.compareAndSet(true, false)) {
                        entered.countDown();
                        await(release);
                    }
                });
                var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = requests.submit(() -> execute(executor, "new"));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                String original = journal.record(owner).orElseThrow().copyId().toString();
                if (copies == 1) {
                    assertThatThrownBy(() -> execute(executor, "new"))
                            .isInstanceOfSatisfying(
                                    ExecutionAdmissionException.class,
                                    failure -> assertThat(failure.reason())
                                            .isEqualTo(ExecutionAdmissionException.Reason.CAPACITY));
                    assertThat(journal.store().copiesOf(account, workspace.value()))
                            .isEqualTo(1);
                } else {
                    var fork = execute(executor, "new");
                    assertThat(fork.copyId()).isNotEqualTo(original);
                    assertThat(execute(executor, fork.copyId()).copyId()).isEqualTo(fork.copyId());
                    assertThat(journal.store().copiesOf(account, workspace.value()))
                            .isEqualTo(2);
                }
            } finally {
                release.countDown();
            }
            assertThat(running.get(5, TimeUnit.SECONDS).exitCode()).isZero();
        }
    }

    @Test
    void recentDefaultAndForkClaimsAreSkippedUntilTheyExpire() {
        try (var executor = executor(4, (run, timeout, handler) -> {})) {
            var original = execute(executor, "new");
            journal.usedRecently(true);
            var fork = execute(executor, "new");
            assertThat(fork.copyId()).isNotEqualTo(original.copyId());
            var forkOwner = owner.forked(UUID.fromString(fork.copyId()));
            clearInvocations(journal.store());
            var next = execute(executor, "new");
            assertThat(next.copyId()).isNotIn(original.copyId(), fork.copyId());
            verify(journal.store(), never()).acquire(forkOwner);
            assertThat(execute(executor, fork.copyId()).copyId()).isEqualTo(fork.copyId());
            journal.usedRecently(false);
            assertThat(execute(executor, "new").copyId()).isEqualTo(original.copyId());
        }
    }

    @Test
    void aForkClaimedAfterInventoryIsNotGivenToAnotherAgent() {
        try (var executor = executor(4, (run, timeout, handler) -> {})) {
            execute(executor, "new");
            journal.usedRecently(true);
            var fork = execute(executor, "new");
            var forkOwner = owner.forked(UUID.fromString(fork.copyId()));
            // Inventory is stale; the selected owner's locked record must decide admission.
            doReturn(List.of(forkOwner)).when(journal.store()).unclaimedForksOf(any(), any());
            assertThat(execute(executor, "new").copyId()).isNotEqualTo(fork.copyId());
            assertThat(execute(executor, fork.copyId()).copyId()).isEqualTo(fork.copyId());
            assertThat(journal.store().copiesOf(account, workspace.value())).isEqualTo(3);
        }
    }

    @Test
    void anUnclaimedForkIsReusedWhileTheDefaultCopyIsBusy() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var hold = new AtomicBoolean();
        try (var executor = executor(4, (run, timeout, handler) -> {
                    if (hold.compareAndSet(true, false)) {
                        entered.countDown();
                        await(release);
                    }
                });
                var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            var original = execute(executor, "new");
            journal.usedRecently(true);
            var fork = execute(executor, "new");
            journal.usedRecently(false);
            hold.set(true);
            var running = requests.submit(() -> execute(executor, original.copyId()));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(execute(executor, "new").copyId()).isEqualTo(fork.copyId());
                assertThat(journal.store().copiesOf(account, workspace.value())).isEqualTo(2);
            } finally {
                release.countDown();
            }
            assertThat(running.get(5, TimeUnit.SECONDS).exitCode()).isZero();
        }
    }

    private NativeRepositoryExecutor executor(int copies, NativeCommandObserver observer) {
        when(auth.siteGroup(any())).thenReturn(SiteGroup.CREATOR);
        return executor(
                observer, new ExecutionLimits(4, 2, Map.of(), Map.of(SiteGroup.CREATOR, copies)), System::nanoTime);
    }

    @Override
    RepositoryExecutor.ExecutionResult execute(NativeRepositoryExecutor executor, String copy) {
        return executor.execute(
                principal,
                workspace,
                new RepositoryExecutor.CopyRequest(copy),
                Optional.empty(),
                "printf claim-check",
                Duration.ofSeconds(10),
                cancellation);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Copy-claim fixture did not release its command");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Copy-claim fixture was interrupted", failure);
        }
    }
}
