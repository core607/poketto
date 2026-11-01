package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.SiteGroup;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.ExecutionUnconfirmedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NativeRepositoryLifecycleTests extends NativeRepositoryTestFixture {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @AfterEach
    void closeMeters() {
        meters.close();
    }

    @Test
    void healthChecksNativeCapabilitiesWithoutOpeningOrExportingACopy() {
        try (var executor = executor((run, timeout, handler) -> {})) {
            var health = new ExecutorHealthIndicator(Optional.of(executor));
            assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
            SandcrabProtocol.Info original = client.info();
            when(client.info())
                    .thenReturn(new SandcrabProtocol.Info(
                            1, epoch, Set.of("runs", "sessions", "files"), original.profiles()));
            assertThat(health.health().getStatus().getCode()).isEqualTo("DOWN");
            when(client.info()).thenThrow(new SandcrabClient.Rejected(503, "NOT_READY"));
            assertThat(health.health().getStatus().getCode()).isEqualTo("DOWN");
            assertThat(health.health().getDetails()).isEmpty();
            verify(client, never()).open(any());
            verify(exports, never()).create(any(), any(), any());
        }
        assertThat(new ExecutorHealthIndicator(Optional.empty())
                        .health()
                        .getStatus()
                        .getCode())
                .isEqualTo("UP");
    }

    @Test
    void monitoringCountsWarmReuseAndConfirmedReleaseWithoutIdentityLabels() {
        try (var executor = executor((run, timeout, handler) -> {
            assertThat(gauge(meters, "operations.active")).isEqualTo(1);
            assertThat(gauge(meters, "sessions.active")).isEqualTo(1);
        })) {
            executor.bindMetrics(meters);
            String copy = execute(executor, "new").copyId();
            execute(executor, copy);
            assertThat(gauge(meters, "operations.active")).isZero();
            assertThat(gauge(meters, "sessions.active")).isEqualTo(1);
            assertThat(counter(meters, "sessions.created")).isEqualTo(1);
            assertThat(counter(meters, "sessions.released")).isZero();
            executor.close();
            assertThat(gauge(meters, "sessions.active")).isZero();
            assertThat(counter(meters, "sessions.released")).isEqualTo(1);
            assertThat(new ExecutorHealthIndicator(Optional.of(executor))
                            .health()
                            .getStatus()
                            .getCode())
                    .isEqualTo("DOWN");
            assertThat(meters.getMeters())
                    .allSatisfy(meter -> assertThat(meter.getId().getTags())
                            .allSatisfy(tag -> assertThat(tag.getKey()).isEqualTo("reason")));
        }
    }

    @Test
    void unconfirmedContainmentKeepsBothOperationAndSessionChargedUntilReconciled() {
        try (var executor = executor((run, timeout, handler) -> {
            closeFails.set(true);
            throw new WorkerUnavailableException();
        })) {
            executor.bindMetrics(meters);
            assertThatThrownBy(() -> execute(executor, "new")).isInstanceOf(ExecutionUnconfirmedException.class);
            assertThat(gauge(meters, "operations.active")).isEqualTo(1);
            assertThat(gauge(meters, "sessions.active")).isEqualTo(1);
            assertThat(counter(meters, "sessions.released")).isZero();
            closeFails.set(false);
            executor.reconcile();
            assertThat(gauge(meters, "operations.active")).isZero();
            assertThat(gauge(meters, "sessions.active")).isZero();
            assertThat(counter(meters, "sessions.released")).isEqualTo(1);
            assertThat(journal.record(owner)).isPresent();
        }
    }

    @Test
    void failedAccountAuthorityLookupReturnsGlobalPermitForTheNextCommand() {
        var lookups = new AtomicInteger();
        when(auth.siteGroup(principal)).thenAnswer(call -> {
            if (lookups.getAndIncrement() == 0) {
                throw new AuthException(AuthException.Code.DENIED);
            }
            return SiteGroup.VIEWER;
        });
        var limits = new ExecutionLimits(1, 1, Map.of(SiteGroup.VIEWER, 1), Map.of());
        try (var executor = executor((run, timeout, handler) -> {}, limits, System::nanoTime)) {
            executor.bindMetrics(meters);
            assertThatThrownBy(() -> execute(executor, "new")).isInstanceOf(AuthException.class);
            assertThat(gauge(meters, "operations.active")).isZero();
            assertThat(gauge(meters, "sessions.active")).isZero();
            assertThat(execute(executor, "new").exitCode()).isZero();
            assertThat(gauge(meters, "operations.active")).isZero();
        }
    }

    @Test
    void journalReleaseFailureAfterExecutionNeverClaimsTheCommandWasRefused() {
        try (var executor = executor((run, timeout, handler) -> journal.failNextClose())) {
            executor.bindMetrics(meters);
            assertThatThrownBy(() -> execute(executor, "new"))
                    .isInstanceOfSatisfying(ExecutionUnconfirmedException.class, failure -> {
                        assertThat(failure.copyId())
                                .isEqualTo(journal.record(owner)
                                        .orElseThrow()
                                        .copyId()
                                        .toString());
                        assertThat(failure.getCause()).isInstanceOf(RetainedCopyException.class);
                    });
            assertThat(commands)
                    .filteredOn(value -> value.equals("printf user-command"))
                    .hasSize(1);
            assertThat(journal.record(owner).orElseThrow().phase()).isEqualTo(AccountCopyRecord.Phase.READY);
            assertThat(gauge(meters, "operations.active")).isZero();
            assertThat(meters.get("poketto.executor.admission.rejected")
                            .tag("reason", "session_busy")
                            .functionCounter()
                            .count())
                    .isZero();
        }
    }

    private static double gauge(SimpleMeterRegistry meters, String name) {
        return meters.get("poketto.executor." + name).gauge().value();
    }

    @Test
    void aFullJournalReportsCapacityBeforeExportOrNativeAdmission() {
        journal.fillPool();
        try (var executor = executor((run, timeout, handler) -> {})) {
            executor.bindMetrics(meters);
            assertThatThrownBy(() -> execute(executor, "new"))
                    .isInstanceOfSatisfying(
                            ExecutionAdmissionException.class,
                            failure -> assertThat(failure.reason())
                                    .isEqualTo(ExecutionAdmissionException.Reason.CAPACITY));
            assertThat(gauge(meters, "operations.active")).isZero();
            assertThat(meters.get("poketto.executor.admission.rejected")
                            .tag("reason", "copy_limit")
                            .functionCounter()
                            .count())
                    .isEqualTo(1);
            verify(client, never()).open(any());
            verify(exports, never()).create(any(), any(), any());
        }
    }

    @Test
    void aContendedJournalReportsBusyBeforeNativeAdmission() throws Exception {
        AccountCopyRecord record = legacyCopy();
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var threads = Executors.newSingleThreadExecutor();
                var executor = executor((run, timeout, handler) -> {})) {
            executor.bindMetrics(meters);
            var holder = threads.submit(() -> {
                try (var held = new AccountCommand(journal.store(), owner, cancellation)) {
                    acquired.countDown();
                    release.await();
                    return held.record().copyId();
                }
            });
            try {
                assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> execute(executor, record.copyId().toString()))
                        .isInstanceOfSatisfying(
                                ExecutionAdmissionException.class,
                                failure -> assertThat(failure.reason())
                                        .isEqualTo(ExecutionAdmissionException.Reason.BUSY));
                assertThat(gauge(meters, "operations.active")).isZero();
                assertThat(meters.get("poketto.executor.admission.rejected")
                                .tag("reason", "session_busy")
                                .functionCounter()
                                .count())
                        .isEqualTo(1);
                verify(client, never()).open(any());
            } finally {
                release.countDown();
            }
            assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo(record.copyId());
        }
    }

    private static double counter(SimpleMeterRegistry meters, String name) {
        return meters.get("poketto.executor." + name).functionCounter().count();
    }
}
