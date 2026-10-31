package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.Capability;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@EnabledOnOs(OS.LINUX)
class NativeExportCancellationTests extends NativeExportFixture {
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void cancellationBeforeOrAfterReserveRetainsAdmissionAndCleansLateCompletion(int blockedAuthorization)
            throws Exception {
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var cleaned = new CountDownLatch(1);
        var authorizations = new AtomicInteger();
        var closed = new AtomicInteger();
        var firstClient = new AtomicReference<String>();
        Set<String> clients = ConcurrentHashMap.newKeySet();
        doAnswer(call -> {
                    Optional<String> client = call.getArgument(4);
                    firstClient.compareAndSet(null, client.orElseThrow());
                    clients.add(client.orElseThrow());
                    return call.callRealMethod();
                })
                .when(packages)
                .create(any(), any(), any(), anyBoolean(), any());
        doAnswer(call -> {
                    call.callRealMethod();
                    if (call.getArgument(2).equals(firstClient.get()) && closed.incrementAndGet() == 2) {
                        cleaned.countDown();
                    }
                    return null;
                })
                .when(packages)
                .closeClient(any(), any(), anyString());
        doAnswer(call -> {
                    if (Thread.currentThread().getName().equals("native-export-build")
                            && authorizations.incrementAndGet() == blockedAuthorization) {
                        entered.countDown();
                        resumeIgnoringInterrupt(resume);
                    }
                    return authorized();
                })
                .when(auth)
                .authorize(any(), any(), any(Capability[].class));
        var command = new FutureTask<>(() -> command(List.of("private/secret.md"), "cancelled.zip", false));
        Thread.ofVirtual().start(command);
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            expired.set(true);
            assertThatThrownBy(() -> command.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(WorkerUnavailableException.class);
            assertThat(uploads.get()).isZero();
            if (blockedAuthorization == 2) {
                assertThat(nextCommand().code()).isEqualTo("EXPORT_CAPACITY");
                assertThat(clients).hasSize(2);
            }
        } finally {
            resume.countDown();
        }
        assertThat(cleaned.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(fixture.retainedPackages()).isZero();
        assertThat(worktree).doesNotContainKey("cancelled.zip");
    }

    private BridgeReplies.Reply nextCommand() {
        NativeRepositoryLease next = mock(NativeRepositoryLease.class);
        when(next.remaining()).thenReturn(Duration.ofSeconds(30));
        when(next.transferLimit()).thenReturn(1024L * 1024 * 1024);
        return exports.command(actor, held, next, arguments(List.of("private/secret.md"), "next.zip", false));
    }

    private static void resumeIgnoringInterrupt(CountDownLatch resume) {
        // Model a provider call that cannot be interrupted; it may return after closeClient ran before reserve.
        while (true) {
            try {
                resume.await();
                return;
            } catch (InterruptedException ignored) {
                // The production owner must still clean a late success, without releasing the real builder early.
            }
        }
    }
}
