package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.mcp.SessionReplacedException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Duration;
import java.util.Optional;

/** Runs timeout and explicit disposal through the real worker with retention disabled. */
record EphemeralLifecycleNativeProbe(RepositoryExecutor executor, AuthPrincipal actor, WorkspaceId workspace) {
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
        assertThat(first.retention()).isNull();
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
        discardAndReopen(first);
    }

    private void discardAndReopen(RepositoryExecutor.ExecutionResult first) {
        assertThatThrownBy(() -> execute("new", "touch should-not-run", 30))
                .isInstanceOfSatisfying(
                        SessionReplacedException.class,
                        failure ->
                                assertThat(failure.reason()).isEqualTo(SessionReplacedException.Reason.DIFFERENT_COPY));
        var request = new RepositoryExecutor.DiscardRequest(first.copyId(), null);
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
                                new RepositoryExecutor.DiscardRequest(replacement.copyId(), null),
                                CANCELLATION)
                        .status())
                .isEqualTo(RepositoryExecutor.DiscardStatus.DISCARDED);
    }

    private RepositoryExecutor.ExecutionResult execute(String copy, String command, int seconds) {
        return executor.execute(
                actor,
                workspace,
                "ephemeral-lifecycle",
                new RepositoryExecutor.CopyRequest(copy, null, false),
                Optional.empty(),
                command,
                Duration.ofSeconds(seconds),
                CANCELLATION);
    }
}
