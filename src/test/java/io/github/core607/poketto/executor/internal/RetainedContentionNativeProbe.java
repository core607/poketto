package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.executor.internal.RetainedOriginalNativeProbe.Adapter;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.ExecutionUnconfirmedException;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Injects explicit contention at the worker reply boundary; subsequent execution and recovery use real SRT. */
record RetainedContentionNativeProbe(
        AuthService auth, AuthPrincipal actor, WorkspaceId workspace, ExecutionCancellation cancellation) {
    void run(Path root, PublicExecutionNativeFixture fixture, Adapter adapter) throws Exception {
        var store = new RetainedCopyStore(
                root.resolve("contention-records"),
                new RetainedCopyStore.Limits(4, 8 * 1024 * 1024, 64 * 1024 * 1024, 0, Duration.ofMinutes(10)),
                Clock.systemUTC());
        RetainedCopyRecord interrupted;
        try (var executor = adapter.open(store, fixture.reader(auth))) {
            var first = execute(
                    executor,
                    new RepositoryExecutor.CopyRequest("new", null, false),
                    "printf 'before saved' > private/contention.md",
                    Duration.ofSeconds(30));
            var copy = new RepositoryExecutor.CopyRequest(
                    first.copyId(), first.retention().generation(), false);
            saveThroughContention(executor, fixture, copy);
            interrupted = timeout(executor, store, copy);
        }
        try (var executor = adapter.open(store, fixture.reader(auth))) {
            var restored = execute(
                    executor,
                    new RepositoryExecutor.CopyRequest(interrupted.copyId().toString(), interrupted.generation(), true),
                    "set -eu; test \"$(cat private/contention.md)\" = 'after save'; test ! -e private/timed-out.md",
                    Duration.ofSeconds(30));
            assertThat(restored.retention().generation()).isEqualTo(interrupted.generation() + 1);
            assertThat(restored.retention().lastInterruptedCommand())
                    .isEqualTo(interrupted.command().id());
            assertThat(restored.commit())
                    .isEqualTo(interrupted.acknowledged().state().originalCommit());
        }
    }

    private void saveThroughContention(
            IsolatedRepositoryExecutor executor,
            PublicExecutionNativeFixture fixture,
            RepositoryExecutor.CopyRequest copy)
            throws Exception {
        Field field = IsolatedRepositoryExecutor.class.getDeclaredField("worker");
        field.setAccessible(true);
        WorkerClient worker = (WorkerClient) field.get(executor);
        var refused = new AtomicInteger();
        var executions = new AtomicInteger();
        field.set(executor, contend(worker, refused, executions));
        int before = fixture.pushes();
        try {
            execute(
                    executor,
                    copy,
                    "set -eu; poketto save private/contention.md; printf 'after save' > private/contention.md",
                    Duration.ofSeconds(30));
            assertThat(refused.get()).isEqualTo(2);
            assertThat(executions.get()).isEqualTo(1);
            assertThat(fixture.pushes()).isEqualTo(before + 1);
            assertThat(fixture.reader(auth)
                            .getFile(actor, workspace, Optional.empty(), "private/contention.md")
                            .source())
                    .contains("before saved");
        } finally {
            field.set(executor, worker);
        }
    }

    private WorkerClient contend(WorkerClient worker, AtomicInteger refused, AtomicInteger executions) {
        var json = JsonMapper.builder().build();
        var firstData = new AtomicReference<JsonNode>();
        WorkerClient intercepted = spy(worker);
        doAnswer(call -> {
                    WorkerClient.PreparedRequest request = call.getArgument(0);
                    JsonNode payload = json.readTree(
                            Base64.getUrlDecoder().decode(request.envelope().payload()));
                    String operation = payload.path("operation").stringValue();
                    if (operation.equals("EXEC")) {
                        executions.incrementAndGet();
                    }
                    if (operation.equals("CHECKPOINT_ACTIVE") && refused.get() < 2) {
                        JsonNode data = payload.path("data");
                        firstData.compareAndSet(null, data);
                        assertThat(data).isEqualTo(firstData.get());
                        refused.incrementAndGet();
                        return json.readTree("{\"ok\":false,\"code\":\"CHECKPOINT_UNAVAILABLE\",\"reason\":\"BUSY\"}");
                    }
                    return worker.send(request, call.getArgument(1));
                })
                .when(intercepted)
                .send(any(), any());
        return intercepted;
    }

    private RetainedCopyRecord timeout(
            IsolatedRepositoryExecutor executor, RetainedCopyStore store, RepositoryExecutor.CopyRequest copy) {
        var failure = catchThrowableOfType(
                ExecutionUnconfirmedException.class,
                () -> execute(
                        executor,
                        copy,
                        "printf 'unacknowledged' > private/timed-out.md; sleep 3",
                        Duration.ofSeconds(1)));
        assertThat(failure).isNotNull();
        assertThat(failure.copyId()).isEqualTo(copy.id());
        assertThat(failure.retention().generation()).isEqualTo(copy.generation());
        assertThat(failure.recoveryAvailable()).isTrue();
        var record = store.read(
                new RetainedCopyRecord.Owner(actor.subjectId(), workspace.value()), UUID.fromString(copy.id()));
        assertThat(record.command()).isNotNull();
        assertThat(record.acknowledged()
                        .state()
                        .fileBaselines()
                        .get("private/contention.md")
                        .source())
                .isEqualTo("before saved");
        return record;
    }

    private RepositoryExecutor.ExecutionResult execute(
            IsolatedRepositoryExecutor executor,
            RepositoryExecutor.CopyRequest copy,
            String command,
            Duration timeout) {
        var result = executor.execute(
                actor, workspace, "contention", copy, Optional.empty(), command, timeout, cancellation);
        assertThat(result.exitCode())
                .as("%s %s", result.stdout(), result.stderr())
                .isZero();
        return result;
    }
}
