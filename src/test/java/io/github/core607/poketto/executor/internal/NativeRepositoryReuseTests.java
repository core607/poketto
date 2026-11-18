package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthRevocation;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NativeRepositoryReuseTests extends NativeRepositoryTestFixture {
    @Test
    void ordinaryNonzeroExitKeepsTheParentAndStartsANewCommandShell() {
        runState = "failed";
        exitCode = 7;
        try (var executor = executor((run, timeout, handler) -> {})) {
            var first = execute(executor, "new");
            var next = execute(executor, first.copyId());
            assertThat(first.exitCode()).isEqualTo(7);
            assertThat(next.exitCode()).isEqualTo(7);
            assertThat(first.freshSandbox()).isTrue();
            assertThat(next.freshSandbox()).isTrue();
            verify(keyed).ensure(any(), any(), any());
            verify(client, never()).openChild(any(), any());
            assertThat(commands.stream().filter("printf user-command"::equals)).hasSize(2);
        }
    }

    @Test
    void changingGrantBindsTheSameGenerationWithFreshAuthorization() {
        try (var executor = executor((run, timeout, handler) -> {})) {
            String copy = execute(executor, "new").copyId();
            AccountCopyRecord before = journal.record(owner).orElseThrow();
            var successor = mock(AuthPrincipal.class);
            when(successor.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
            when(successor.accountId()).thenReturn(account);
            when(successor.subjectId()).thenReturn(UUID.randomUUID());
            var result = executor.execute(
                    successor,
                    workspace,
                    new RepositoryExecutor.CopyRequest(copy),
                    Optional.empty(),
                    "printf user-command",
                    Duration.ofSeconds(1),
                    cancellation);
            AccountCopyRecord after = journal.record(owner).orElseThrow();
            assertThat(result.freshSandbox()).isTrue();
            assertThat(after.writer().principalId()).isEqualTo(successor.subjectId());
            assertThat(after.writer().storage()).isEqualTo(before.writer().storage());
            assertThat(after.original()).isEqualTo(before.original());
            assertThat(after.state()).isEqualTo(before.state());
            assertThat(after.writer().storage().workspaceGeneration())
                    .isEqualTo(before.writer().storage().workspaceGeneration());
            verify(client, never()).open(any());
        }
    }

    @Test
    void anIdleWorkspaceHoldsNoApplicationCommandOrReusableGrant() {
        try (var executor = executor((run, timeout, handler) -> {})) {
            String copy = execute(executor, "new").copyId();
            executor.revoked(new AuthRevocation(workspace, Set.of(account), Set.of()));
            when(auth.authorize(any(), any(), any(io.github.core607.poketto.auth.Capability[].class)))
                    .thenThrow(new io.github.core607.poketto.auth.AuthException(
                            io.github.core607.poketto.auth.AuthException.Code.DENIED));
            assertThatThrownBy(() -> execute(executor, copy))
                    .isInstanceOf(io.github.core607.poketto.auth.AuthException.class);
            verify(keyed, times(1)).submit(any(), any(), any());
            assertThat(journal.record(owner).orElseThrow().phase()).isEqualTo(AccountCopyRecord.Phase.READY);
        }
    }

    @Test
    void aKnownCapacityRefusalKeepsTheWarmRuntimeAndNeverMarksTheCommandUncertain() {
        try (var executor = executor((run, timeout, handler) -> {})) {
            String copy = execute(executor, "new").copyId();
            refuseNextCommand.set(true);
            assertThatThrownBy(() -> execute(executor, copy))
                    .isInstanceOfSatisfying(
                            ExecutionAdmissionException.class,
                            refused -> assertThat(refused.reason())
                                    .isEqualTo(ExecutionAdmissionException.Reason.CAPACITY));
            assertThat(journal.record(owner).orElseThrow().phase()).isEqualTo(AccountCopyRecord.Phase.READY);
            assertThat(journal.record(owner).orElseThrow().executionId()).isNull();
            assertThat(execute(executor, copy).freshSandbox()).isTrue();
            verify(keyed).ensure(any(), any(), any());
            assertThat(commands.stream().filter("printf user-command"::equals)).hasSize(2);
        }
    }

    @Test
    void anExpiredWarmRuntimeIsReplacedBeforeSubmittingTheRequestedCommandOnce() {
        try (var executor = executor((run, timeout, handler) -> {})) {
            String copy = execute(executor, "new").copyId();
            sessions.clear();
            assertThat(execute(executor, copy).freshSandbox()).isTrue();
            verify(client, never()).open(any());
            verify(exports).create(any(), any(), any());
            assertThat(commands.stream().filter("printf user-command"::equals)).hasSize(2);
        }
    }

    @Test
    void aDifferentGenerationAtTheSameKeyCannotRunOrDisposeTheReplacement() {
        try (var executor = executor((run, timeout, handler) -> {})) {
            String id = execute(executor, "new").copyId();
            UUID copy = UUID.fromString(id);
            var first = handles.get(copy);
            handles.put(
                    copy,
                    new SandcrabWorkspaces.Handle(
                            first.id(),
                            first.key(),
                            UUID.randomUUID().toString(),
                            epoch,
                            first.profile(),
                            first.templateId(),
                            "ready",
                            first.submissionWindow()));
            assertThatThrownBy(() -> execute(executor, id)).isInstanceOf(ExecutionAdmissionException.class);
            assertThatThrownBy(() -> executor.discard(
                            principal, workspace, new RepositoryExecutor.DiscardRequest(id), cancellation))
                    .isInstanceOf(ExecutionAdmissionException.class);
            assertThat(commands).containsExactly("printf user-command");
            verify(keyed, never()).remove(any(), any());
            assertThat(journal.record(owner)).isPresent();
        }
    }

    @Test
    void aFailedControllerInitializationRetainsBoundedDiagnostics() {
        var failure = new SandcrabProtocol.Result(1, "", "setup failed", false, "SHELL_INIT_FAILED", null);
        assertThat(failure.success()).isFalse();
        assertThat(failure.stderr()).isEqualTo("setup failed");
        assertThatThrownBy(() -> new SandcrabProtocol.Result(1, "", "output", false, "RESULT_CAPACITY", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnobservedCommandIsFencedBeforeTheSameCopyAcceptsAnotherWrite() {
        var loseReceipt = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (var executor = executor((ref, timeout, handler) -> {
            if (loseReceipt.getAndSet(false)) {
                runs.remove(ref.id());
                throw new WorkerUnavailableException();
            }
        })) {
            assertThatThrownBy(() -> execute(executor, "new"))
                    .isInstanceOf(io.github.core607.poketto.mcp.ExecutionUnconfirmedException.class);
            var record = journal.record(owner).orElseThrow();
            assertThat(handles).doesNotContainKey(record.copyId());
            assertThat(execute(executor, record.copyId().toString()).retention().lastInterruptedCommand())
                    .isEqualTo(record.executionId());
            assertThat(commands).containsExactly("printf user-command", "printf user-command");
            verify(keyed).remove(any(), any());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aRefusedReattachmentPreservesTheCopyForReconnectOrDirectDisposal(boolean capacity) {
        String copy;
        try (var first = executor((run, timeout, handler) -> {})) {
            copy = execute(first, "new").copyId();
        }
        var before = journal.record(owner).orElseThrow();
        RuntimeException refusal = capacity
                ? new SandcrabClient.Rejected(429, "SESSION_CAPACITY")
                : new WorkerUnavailableException(new java.io.IOException("connection refused before send"), false);
        var known = handles.get(before.copyId());
        org.mockito.Mockito.doThrow(refusal)
                .doReturn(Optional.of(known))
                .when(keyed)
                .find(before.copyId());
        try (var resumed = executor((run, timeout, handler) -> {})) {
            assertThatThrownBy(() -> execute(resumed, copy)).isInstanceOf(ExecutionAdmissionException.class);
            var retained = journal.record(owner).orElseThrow();
            assertThat(retained.phase()).isEqualTo(AccountCopyRecord.Phase.READY);
            assertThat(retained.writer()).isEqualTo(before.writer());
            assertThat(retained.original()).isEqualTo(before.original());
            assertThat(retained.state()).isEqualTo(before.state());
            assertThat(commands.stream().filter("printf user-command"::equals)).hasSize(1);
            if (capacity) {
                assertThat(execute(resumed, copy).copyId()).isEqualTo(copy);
            }
            assertThat(resumed.discard(principal, workspace, new RepositoryExecutor.DiscardRequest(copy), cancellation)
                            .status())
                    .isEqualTo(RepositoryExecutor.DiscardStatus.DISCARDED);
            assertThat(journal.record(owner)).isEmpty();
        }
    }

    @Test
    void lostWorkspaceCreationRetainsTheOriginalAndNeverSubmitsAUserCommand() {
        String copy;
        try (var first = executor((run, timeout, handler) -> {})) {
            copy = execute(first, "new").copyId();
        }
        AccountCopyRecord before = journal.record(owner).orElseThrow();
        handles.clear();
        doAnswer(call -> {
                    assertThat(journal.record(owner).orElseThrow().phase())
                            .isEqualTo(AccountCopyRecord.Phase.ATTACHING);
                    throw new WorkerUnavailableException();
                })
                .when(keyed)
                .ensure(any(), any(), any());
        try (var resumed = executor((run, timeout, handler) -> {})) {
            assertThatThrownBy(() -> execute(resumed, copy)).isInstanceOf(ExecutionAdmissionException.class);
            AccountCopyRecord pending = journal.record(owner).orElseThrow();
            assertThat(pending.phase()).isEqualTo(AccountCopyRecord.Phase.ATTACHING);
            assertThat(pending.original()).isEqualTo(before.original());
            assertThat(pending.state()).isEqualTo(before.state());
            assertThat(pending.writer().storage().workspaceGeneration()).isNull();
            assertThat(commands).containsExactly("printf user-command");
            resumed.reconcile();
        }
    }
}
