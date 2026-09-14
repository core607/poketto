package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.mcp.SessionReplacedException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Runs account-copy reconnects, timeout and disposal through the real disk worker. */
record EphemeralLifecycleNativeProbe(
        IsolatedRepositoryExecutor executor,
        AuthPrincipal actor,
        WorkspaceId workspace,
        Supplier<IsolatedRepositoryExecutor> reopen) {
    private static final ExecutionCancellation CANCELLATION = new ExecutionCancellation() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public Registration onCancel(Runnable action) {
            return () -> {};
        }
    };

    void run() {
        RepositoryExecutor.ExecutionResult first = execute(
                "new", "printf before > draft.txt; python3 -c \"open('scratch.bin','wb').write(bytes([0,255]))\"", 30);
        assertThat(first.exitCode()).isZero();
        assertThat(first.retention()).isNotNull();
        assertThat(first.retention().expiresAt()).isGreaterThan(System.currentTimeMillis());
        verifyLocalEditing(first);
        verifyReconnection(first);
        RepositoryExecutor.ExecutionResult timedOut =
                execute(first.copyId(), "printf partial >> draft.txt; (sleep 40; touch late.txt) & wait", 5);
        assertThat(timedOut.copyId()).isEqualTo(first.copyId());
        assertThat(timedOut.timedOut()).isTrue();
        assertThat(timedOut.terminationReason()).isEqualTo(RepositoryExecutor.TerminationReason.TIMEOUT);
        RepositoryExecutor.ExecutionResult inspected = execute(
                first.copyId(),
                "set -eu; test \"$(cat draft.txt)\" = beforepartial; test ! -e late.txt; "
                        + "python3 -c \"assert open('scratch.bin','rb').read() == bytes([0,255])\"; git rev-parse HEAD",
                30);
        assertThat(inspected.exitCode()).isZero();
        assertThat(inspected.commit()).isEqualTo(first.commit());
        verifyApplicationRestart(first);
    }

    private void verifyApplicationRestart(RepositoryExecutor.ExecutionResult first) {
        executor.close();
        try (var restored = reopen.get()) {
            var probe = new EphemeralLifecycleNativeProbe(restored, actor, workspace, reopen);
            var continued = probe.execute(
                    first.copyId(),
                    "set -eu; test \"$(cat draft.txt)\" = beforepartial; "
                            + "python3 -c \"assert open('scratch.bin','rb').read() == bytes([0,255])\"; git rev-parse HEAD",
                    30);
            assertThat(continued.exitCode())
                    .describedAs(continued.stdout() + continued.stderr())
                    .isZero();
            assertThat(continued.copyId()).isEqualTo(first.copyId());
            assertThat(continued.commit()).isEqualTo(first.commit());
            assertThat(continued.retention().resumed()).isTrue();
            probe.discardAndReopen(first);
        }
    }

    private void verifyLocalEditing(RepositoryExecutor.ExecutionResult first) {
        RepositoryExecutor.ExecutionResult edited = execute(first.copyId(), """
                set -eu
                poketto create private/edit-check.md --text 'alpha beta'
                poketto edit private/edit-check.md --old alpha --new changed
                poketto edit private/edit-check.md --old beta --new other
                test "$(cat private/edit-check.md)" = 'changed other'
                if poketto edit private/edit-check.md --old alpha --new stale > /tmp/edit-result; then exit 91; fi
                grep -q OLD_TEXT_NOT_FOUND /tmp/edit-result
                if poketto create private/edit-check.md --text overwrite > /tmp/edit-result; then exit 92; fi
                grep -q ALREADY_EXISTS /tmp/edit-result
                test "$(cat private/edit-check.md)" = 'changed other'
                poketto create private/ambiguous-check.md --text 'same same'
                if poketto edit private/ambiguous-check.md --old same --new lost > /tmp/edit-result; then exit 93; fi
                grep -q AMBIGUOUS_MATCH /tmp/edit-result
                test "$(cat private/ambiguous-check.md)" = 'same same'
                if poketto edit private/edit-check.md --old '' --new lost > /tmp/edit-result; then exit 94; fi
                test "$(cat private/edit-check.md)" = 'changed other'
                ! git cat-file -e HEAD:private/edit-check.md 2>/dev/null
                """, 30);
        assertThat(edited.exitCode())
                .describedAs(edited.stdout() + edited.stderr())
                .isZero();
        assertThat(edited.commit()).isEqualTo(first.commit());
    }

    private void discardAndReopen(RepositoryExecutor.ExecutionResult first) {
        assertThat(execute("new", "pwd", 30).copyId()).isEqualTo(first.copyId());
        assertThatThrownBy(() -> execute(UUID.randomUUID().toString(), "touch should-not-run", 30))
                .isInstanceOfSatisfying(
                        SessionReplacedException.class,
                        failure ->
                                assertThat(failure.reason()).isEqualTo(SessionReplacedException.Reason.DIFFERENT_COPY));
        var request = new RepositoryExecutor.DiscardRequest(first.copyId());
        assertThat(executor.discard(actor, workspace, request, CANCELLATION).status())
                .isEqualTo(RepositoryExecutor.DiscardStatus.DISCARDED);
        assertThat(executor.discard(actor, workspace, request, CANCELLATION).status())
                .isEqualTo(RepositoryExecutor.DiscardStatus.ABSENT);
        RepositoryExecutor.ExecutionResult replacement =
                execute("new", "test ! -e draft.txt && test ! -e scratch.bin && test ! -e should-not-run", 30);
        assertThat(replacement.exitCode()).isZero();
        assertThat(replacement.copyId()).isNotEqualTo(first.copyId());
        assertThat(replacement.commit()).isEqualTo(first.commit());
        assertThat(executor.discard(
                                actor,
                                workspace,
                                new RepositoryExecutor.DiscardRequest(replacement.copyId()),
                                CANCELLATION)
                        .status())
                .isEqualTo(RepositoryExecutor.DiscardStatus.DISCARDED);
    }

    private RepositoryExecutor.ExecutionResult execute(String copy, String command, int seconds) {
        return executeAs(actor, copy, command, seconds);
    }

    private void verifyReconnection(RepositoryExecutor.ExecutionResult first) {
        assertThat(execute(first.copyId(), "test \"$(cat draft.txt)\" = before", 30)
                        .exitCode())
                .isZero();
        AuthPrincipal otherGrant = mock(AuthPrincipal.class);
        UUID accountId = actor.accountId();
        when(otherGrant.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(otherGrant.subjectId()).thenReturn(UUID.randomUUID());
        when(otherGrant.accountId()).thenReturn(accountId);
        var continued = executeAs(
                otherGrant,
                first.copyId(),
                "set -eu; test \"$(cat draft.txt)\" = before; test \"$(cat private/edit-check.md)\" = 'changed other'",
                30);
        assertThat(continued.exitCode())
                .describedAs(continued.stdout() + continued.stderr())
                .isZero();
        assertThat(continued.copyId()).isEqualTo(first.copyId());
        assertThat(continued.commit()).isEqualTo(first.commit());
    }

    private RepositoryExecutor.ExecutionResult executeAs(
            AuthPrincipal current, String copy, String command, int seconds) {
        return executor.execute(
                current,
                workspace,
                "transport-" + UUID.randomUUID(),
                new RepositoryExecutor.CopyRequest(copy),
                Optional.empty(),
                command,
                Duration.ofSeconds(seconds),
                CANCELLATION);
    }
}
