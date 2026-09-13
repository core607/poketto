package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.executor.internal.RetainedOriginalNativeProbe.Adapter;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses the native supervisor's actual KILL/restart control; auth remains the surrounding synthetic fixture. */
record RetainedLifecycleNativeProbe(
        AuthService auth, AuthPrincipal actor, WorkspaceId workspace, ExecutionCancellation cancellation) {
    void run(
            Path root,
            PublicExecutionNativeFixture fixture,
            Adapter adapter,
            Control control,
            AtomicBoolean privateRead)
            throws Exception {
        var records = new RetainedCopyStore(
                root.resolve("lifecycle-records"),
                new RetainedCopyStore.Limits(4, 8 * 1024 * 1024, 64 * 1024 * 1024, 0, Duration.ofMinutes(10)),
                Clock.systemUTC());
        boolean previous = privateRead.getAndSet(true);
        try {
            privateRestart(fixture, adapter, records, control);
            publicRestart(fixture, adapter, records, control, privateRead);
        } finally {
            privateRead.set(previous);
        }
    }

    private void privateRestart(
            PublicExecutionNativeFixture fixture, Adapter adapter, RetainedCopyStore records, Control control)
            throws Exception {
        try (var executor = adapter.open(records, fixture.reader(auth))) {
            var first = execute(
                    executor,
                    "restart-private",
                    fresh(),
                    "set -eu; printf 'retained before worker restart' > private/restart.txt; "
                            + "python3 -c \"from pathlib import Path; Path('private/restart.bin').write_bytes(bytes([0,255,7]))\"");
            var before = record(records, first);
            control.restartWorker();
            var restored = execute(
                    executor,
                    "restart-private-restored",
                    resume(before),
                    "set -eu; test \"$(cat private/restart.txt)\" = 'retained before worker restart'; "
                            + "test \"$(git rev-parse HEAD)\" = "
                            + before.acknowledged().state().originalCommit() + "; "
                            + "python3 -c \"from pathlib import Path; assert Path('private/restart.bin').read_bytes() == bytes([0,255,7])\"; "
                            + "poketto save private/restart.txt");
            var after = record(records, restored);
            assertThat(after.writer().workerBootId())
                    .isNotEqualTo(before.writer().workerBootId());
            assertThat(after.copyId()).isEqualTo(before.copyId());
            assertThat(after.generation()).isEqualTo(before.generation() + 1);
            assertThat(after.originalBaseline()).isEqualTo(before.originalBaseline());
            denyPrivateRecovery(executor, records, after);
        }
    }

    private void denyPrivateRecovery(
            IsolatedRepositoryExecutor executor, RetainedCopyStore records, RetainedCopyRecord before) {
        var denied = new AuthException(AuthException.Code.DENIED);
        doThrow(denied)
                .when(auth)
                .authorize(eq(actor), eq(workspace), eq(Capability.READ_PRIVATE), eq(Capability.EXECUTE_REPOSITORY));
        try {
            assertThatThrownBy(() ->
                            execute(executor, "restart-private-denied", resume(before), "touch private/forbidden"))
                    .isSameAs(denied);
            assertThat(records.read(before.owner(), before.copyId())).isEqualTo(before);
        } finally {
            doAnswer(call -> auth.authorize(actor, workspace))
                    .when(auth)
                    .authorize(
                            eq(actor), eq(workspace), eq(Capability.READ_PRIVATE), eq(Capability.EXECUTE_REPOSITORY));
        }
    }

    private void publicRestart(
            PublicExecutionNativeFixture fixture,
            Adapter adapter,
            RetainedCopyStore records,
            Control control,
            AtomicBoolean privateRead)
            throws Exception {
        privateRead.set(false);
        try (var executor = adapter.open(records, fixture.reader(auth))) {
            var first = execute(
                    executor, "restart-public", fresh(), "printf 'public local draft' > scratch.md; test ! -e private");
            var before = record(records, first);
            assertThat(before.fullRead()).isFalse();
            assertThat(before.originalBaseline()).isNull();
            control.restartWorker();
            var result = execute(executor, "restart-public-restored", resume(before), publicCommand(before));
            var restored = record(records, result);
            assertThat(restored.writer().workerBootId())
                    .isNotEqualTo(before.writer().workerBootId());
            assertThat(restored.publicExport()).isEqualTo(before.publicExport());
            privateRead.set(true);
            result = execute(executor, "restart-public-increased-grant", resume(restored), publicCommand(restored));
            var frozen = record(records, result);
            assertThat(frozen.fullRead()).isFalse();
            assertThat(frozen.originalBaseline()).isNull();
            fixture.withdraw();
            assertThatThrownBy(() -> execute(executor, "restart-public-withdrawn", resume(frozen), "touch forbidden"))
                    .isInstanceOf(ContentRepositoryException.class);
            assertThat(records.read(frozen.owner(), frozen.copyId())).isEqualTo(frozen);
        }
    }

    private static String publicCommand(RetainedCopyRecord record) {
        return "set -eu; test \"$(cat scratch.md)\" = 'public local draft'; test ! -e private; "
                + "test ! -e .poketto/publishing.yaml; test \"$(git rev-parse HEAD)\" = "
                + record.acknowledged().state().originalCommit() + "; "
                + "if git cat-file -e " + record.publicExport().authorityCommit() + "^{commit}; then exit 93; fi";
    }

    private RetainedCopyRecord record(RetainedCopyStore records, RepositoryExecutor.ExecutionResult result) {
        return records.read(
                new RetainedCopyRecord.Owner(actor.subjectId(), workspace.value()), UUID.fromString(result.copyId()));
    }

    private static RepositoryExecutor.CopyRequest fresh() {
        return new RepositoryExecutor.CopyRequest("new", null, false);
    }

    private static RepositoryExecutor.CopyRequest resume(RetainedCopyRecord record) {
        return new RepositoryExecutor.CopyRequest(record.copyId().toString(), record.generation(), true);
    }

    private RepositoryExecutor.ExecutionResult execute(
            IsolatedRepositoryExecutor executor,
            String transport,
            RepositoryExecutor.CopyRequest copy,
            String command) {
        var result = executor.execute(
                actor, workspace, transport, copy, Optional.empty(), command, Duration.ofSeconds(30), cancellation);
        assertThat(result.exitCode())
                .as("%s %s", result.stdout(), result.stderr())
                .isZero();
        return result;
    }

    @FunctionalInterface
    interface Control {
        void restartWorker() throws Exception;
    }
}
