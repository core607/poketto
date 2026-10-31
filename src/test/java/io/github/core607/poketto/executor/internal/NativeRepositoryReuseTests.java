package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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

class NativeRepositoryReuseTests extends NativeRepositoryTestFixture {
    @Test
    void ordinaryNonzeroExitKeepsTheSameRuntimeAndDoesNotRepeatSetup() {
        runState = "failed";
        exitCode = 7;
        try (var executor = executor((run, timeout, handler) -> {})) {
            var first = execute(executor, "new");
            var next = execute(executor, first.copyId());
            assertThat(first.exitCode()).isEqualTo(7);
            assertThat(next.exitCode()).isEqualTo(7);
            assertThat(first.freshSandbox()).isTrue();
            assertThat(next.freshSandbox()).isFalse();
            verify(client).open(any());
            assertThat(commands).hasSize(4);
        }
    }

    @Test
    void changingGrantContainsTheIdleRuntimeBeforeReattachingTheSameVolume() {
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
            assertThat(sessions.get(before.writer().leaseId().toString()).terminal())
                    .isTrue();
            verify(client, times(2)).open(any());
        }
    }

    @Test
    void revocationContainsIdleSessionsAsWellAsInflightCommands() {
        try (var executor = executor((run, timeout, handler) -> {})) {
            execute(executor, "new");
            assertThat(sessions.values()).allMatch(session -> session.state().equals("ready"));
            executor.revoked(new AuthRevocation(workspace, Set.of(account), Set.of()));
            assertThat(sessions.values()).allMatch(SandcrabProtocol.Session::terminal);
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
            assertThat(execute(executor, copy).freshSandbox()).isFalse();
            verify(client).open(any());
            assertThat(commands.stream().filter("printf user-command"::equals)).hasSize(2);
        }
    }

    @Test
    void anExpiredWarmRuntimeIsReplacedBeforeSubmittingTheRequestedCommandOnce() {
        try (var executor = executor((run, timeout, handler) -> {})) {
            String copy = execute(executor, "new").copyId();
            AccountCopyRecord before = journal.record(owner).orElseThrow();
            SandcrabProtocol.Session previous =
                    sessions.get(before.writer().leaseId().toString());
            sessions.put(
                    previous.id(),
                    new SandcrabProtocol.Session(
                            previous.id(),
                            previous.serverId(),
                            previous.profile(),
                            "expired",
                            previous.expiresAtMillis(),
                            null,
                            previous.volumeId(),
                            null,
                            null));
            assertThat(execute(executor, copy).freshSandbox()).isTrue();
            verify(client, times(2)).open(any());
            verify(exports).create(any(), any(), any());
            assertThat(commands.stream().filter("printf user-command"::equals)).hasSize(2);
        }
    }

    @Test
    void lostAttachResponseCannotPublishAReadyJournalOrInferContainmentFromMissingSession() {
        String copy;
        try (var first = executor((run, timeout, handler) -> {})) {
            copy = execute(first, "new").copyId();
        }
        AccountCopyRecord before = journal.record(owner).orElseThrow();
        doAnswer(call -> {
                    assertThat(journal.record(owner).orElseThrow().phase())
                            .isEqualTo(AccountCopyRecord.Phase.ATTACHING);
                    throw new WorkerUnavailableException();
                })
                .when(client)
                .open(any());
        try (var resumed = executor((run, timeout, handler) -> {})) {
            assertThatThrownBy(() -> execute(resumed, copy)).isInstanceOf(ExecutionAdmissionException.class);
            AccountCopyRecord pending = journal.record(owner).orElseThrow();
            assertThat(pending.phase()).isEqualTo(AccountCopyRecord.Phase.ATTACHING);
            assertThat(pending.original()).isEqualTo(before.original());
            assertThat(pending.state()).isEqualTo(before.state());
            assertThatThrownBy(() -> workspaces().fence(pending, client.info()))
                    .isInstanceOf(SandcrabClient.Rejected.class);
            SandcrabProtocol.Info previous = client.info();
            when(client.info())
                    .thenReturn(new SandcrabProtocol.Info(
                            1, UUID.randomUUID().toString(), previous.capabilities(), previous.profiles()));
            resumed.reconcile();
        }
    }
}
