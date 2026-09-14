package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.executor.internal.RetainedOriginalNativeProbe.Adapter;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

record RetainedDiscardNativeProbe(
        AuthService auth, AuthPrincipal actor, WorkspaceId workspace, ExecutionCancellation cancellation) {
    void run(Path root, PublicExecutionNativeFixture fixture, Adapter adapter) throws Exception {
        var store = new RetainedCopyStore(
                root.resolve("discard-records"),
                new RetainedCopyStore.Limits(4, 8 * 1024 * 1024, 64 * 1024 * 1024, 0, Duration.ofMinutes(10)),
                Clock.systemUTC());
        try (var executor = adapter.open(store, fixture.reader(auth))) {
            var first = execute(
                    executor,
                    "discard-copy",
                    new RepositoryExecutor.CopyRequest("new", null, false),
                    "set -eu; printf 'remote saved' > private/discard-saved.md; poketto save private/discard-saved.md; "
                            + "printf 'only local' > private/discard-draft.md");
            var owner = new RetainedCopyRecord.Owner(actor.subjectId(), workspace.value());
            RetainedCopyRecord record = store.read(owner, UUID.fromString(first.copyId()));
            var request = new RepositoryExecutor.DiscardRequest(first.copyId(), record.generation());
            assertThatThrownBy(() -> executor.discard(
                            actor,
                            workspace,
                            new RepositoryExecutor.DiscardRequest(first.copyId(), record.generation() + 1),
                            cancellation))
                    .isInstanceOfSatisfying(
                            ExecutionAdmissionException.class,
                            failure -> assertThat(failure.reason())
                                    .isEqualTo(ExecutionAdmissionException.Reason.GENERATION_MISMATCH));
            try (var writer = store.writer(owner, record.copyId())) {
                writer.requireValid();
                assertThatThrownBy(() -> executor.discard(actor, workspace, request, cancellation))
                        .isInstanceOfSatisfying(
                                ExecutionAdmissionException.class,
                                failure -> assertThat(failure.reason())
                                        .isEqualTo(ExecutionAdmissionException.Reason.BUSY));
            }
            failCloseBeforeDeletion(executor, store, record, request);
            assertThat(executor.discard(actor, workspace, request, cancellation).status())
                    .isEqualTo(RepositoryExecutor.DiscardStatus.DISCARDED);
            assertThat(executor.discard(actor, workspace, request, cancellation).status())
                    .isEqualTo(RepositoryExecutor.DiscardStatus.ABSENT);
            assertThatThrownBy(() -> execute(
                            executor,
                            "discard-reconnect",
                            new RepositoryExecutor.CopyRequest(first.copyId(), record.generation(), true),
                            "touch forbidden"))
                    .isInstanceOfSatisfying(
                            ExecutionAdmissionException.class,
                            failure -> assertThat(failure.reason())
                                    .isEqualTo(ExecutionAdmissionException.Reason.MISSING_COPY));
            execute(
                    executor,
                    "discard-copy",
                    new RepositoryExecutor.CopyRequest("new", null, false),
                    "set -eu; test \"$(cat private/discard-saved.md)\" = 'remote saved'; test ! -e private/discard-draft.md");
        }
    }

    private void failCloseBeforeDeletion(
            IsolatedRepositoryExecutor executor,
            RetainedCopyStore store,
            RetainedCopyRecord record,
            RepositoryExecutor.DiscardRequest request)
            throws Exception {
        Field field = IsolatedRepositoryExecutor.class.getDeclaredField("worker");
        field.setAccessible(true);
        WorkerClient worker = (WorkerClient) field.get(executor);
        var acknowledge = new AtomicBoolean();
        field.set(executor, RetainedCommandNativeProbe.lostCloseAcknowledgement(worker, acknowledge));
        try {
            assertThatThrownBy(() -> executor.discard(actor, workspace, request, cancellation))
                    .isInstanceOf(ExecutionAdmissionException.class);
            assertThat(store.read(record.owner(), record.copyId())).isEqualTo(record);
            RetainedCommandNativeProbe.assertWriterBusy(store, record);
            acknowledge.set(true);
            RetainedCommandNativeProbe.awaitWriterRelease(store, record);
            assertThat(store.read(record.owner(), record.copyId())).isEqualTo(record);
        } finally {
            acknowledge.set(true);
            field.set(executor, worker);
        }
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
}
