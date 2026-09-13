package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.PortableContentExports;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real adapter command checkpoints; direct worker restoration verifies the retained bytes separately. */
final class RetainedCommandNativeProbe {
    private static final ExecutionCancellation CANCELLATION = new ExecutionCancellation() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public Registration onCancel(Runnable terminate) {
            return () -> {};
        }
    };
    private final Path root;
    private final Path exports;
    private final Path socket;
    private final Path key;
    private final AuthService auth;
    private final AuthPrincipal actor;
    private final WorkspaceId workspace;
    private final ObjectMapper json;
    private final RememberingExecutorClient client = new RememberingExecutorClient();

    RetainedCommandNativeProbe(
            Path root,
            Path exports,
            Path socket,
            Path key,
            AuthService auth,
            AuthPrincipal actor,
            WorkspaceId workspace,
            ObjectMapper json) {
        this.root = root;
        this.exports = exports;
        this.socket = socket;
        this.key = key;
        this.auth = auth;
        this.actor = actor;
        this.workspace = workspace;
        this.json = json;
    }

    void run() throws Exception {
        Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var store = new RetainedCopyStore(
                root.resolve("records"),
                new RetainedCopyStore.Limits(8, 8 * 1024 * 1024, 64 * 1024 * 1024, 0, Duration.ofMinutes(10)),
                Clock.systemUTC());
        try (var fixture = new PublicExecutionNativeFixture(root.resolve("repository"), exports, auth, workspace)) {
            try (var executor = new ExecutorConfiguration()
                    .isolatedRepositoryExecutor(
                            Optional.of(store),
                            auth,
                            fixture.exports(),
                            mock(PortableContentExports.class),
                            fixture.media(auth),
                            fixture.reader(auth),
                            fixture.patches(auth),
                            fixture.moves(auth),
                            json,
                            socket,
                            key,
                            4,
                            45,
                            8)) {
                RetainedCopyRecord record = commands(executor, store);
                Field workerField = IsolatedRepositoryExecutor.class.getDeclaredField("worker");
                workerField.setAccessible(true);
                WorkerClient worker = (WorkerClient) workerField.get(executor);
                record = cancelAfterCheckpoint(executor, store, worker, record);
                executor.close();
                restoreBytes(worker, record);
            }
        }
    }

    private RetainedCopyRecord commands(IsolatedRepositoryExecutor executor, RetainedCopyStore store) {
        RepositoryExecutor.ExecutionResult saved = execute(
                executor,
                "set -eu; printf 'acknowledged text' > public/article.md; "
                        + "printf 'unsaved binary' > private/draft.bin; poketto save public/article.md; exit 7");
        assertThat(saved.exitCode()).as("%s %s", saved.stdout(), saved.stderr()).isEqualTo(7);
        var owner = new RetainedCopyRecord.Owner(actor.subjectId(), workspace.value());
        RetainedCopyRecord first = store.read(owner, UUID.fromString(saved.copyId()));
        assertThat(first.command()).isNull();
        assertThat(first.acknowledged().state().baseCommit())
                .isNotEqualTo(first.acknowledged().state().originalCommit());
        assertThat(first.acknowledged().state().baselines()).containsKey("public/article.md");
        assertThat(first.acknowledged().state().originalCommit()).isEqualTo(saved.commit());
        RepositoryExecutor.ExecutionResult imported = execute(
                executor,
                "set -eu; poketto media import private/draft.bin --as private/draft.dat "
                        + "--type application/octet-stream --key retained_native_import_01; "
                        + "test -f private/draft.bin; exit 9");
        assertThat(imported.exitCode())
                .as("%s %s", imported.stdout(), imported.stderr())
                .isEqualTo(9);
        RetainedCopyRecord last = store.read(owner, UUID.fromString(imported.copyId()));
        assertThat(last.command()).isNull();
        assertThat(last.acknowledged().state().baseCommit())
                .isEqualTo(first.acknowledged().state().baseCommit());
        assertThat(last.acknowledged().id()).isNotEqualTo(first.acknowledged().id());
        JsonNode receipt = json.valueToTree(last.acknowledged().state().lastImport());
        assertThat(receipt.path("indexUpdated").asBoolean()).isTrue();
        assertThat(last.generation()).isEqualTo(1);
        return last;
    }

    private RepositoryExecutor.ExecutionResult execute(IsolatedRepositoryExecutor executor, String command) {
        return client.execute(
                executor,
                actor,
                workspace,
                "retained-command",
                Optional.empty(),
                command,
                Duration.ofSeconds(30),
                CANCELLATION);
    }

    private RetainedCopyRecord cancelAfterCheckpoint(
            IsolatedRepositoryExecutor executor,
            RetainedCopyStore store,
            WorkerClient worker,
            RetainedCopyRecord before)
            throws Exception {
        var cancellation = new Cancellation();
        CompletableFuture<RepositoryExecutor.ExecutionResult> running =
                CompletableFuture.supplyAsync(() -> client.execute(
                        executor,
                        actor,
                        workspace,
                        "retained-command",
                        Optional.empty(),
                        "printf 'unconfirmed' > private/unconfirmed.bin; sleep 30",
                        Duration.ofSeconds(30),
                        cancellation));
        awaitRunning(worker, before);
        var acknowledgeClose = new AtomicBoolean();
        WorkerClient intercepted = lostCloseAcknowledgement(worker, acknowledgeClose);
        Field field = IsolatedRepositoryExecutor.class.getDeclaredField("worker");
        field.setAccessible(true);
        field.set(executor, intercepted);
        try {
            assertThatThrownBy(cancellation::cancel).isInstanceOf(WorkerUnavailableException.class);
            assertThatThrownBy(() -> running.get(15, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(RuntimeException.class);
            assertWriterBusy(store, before);
            acknowledgeClose.set(true);
            awaitWriterRelease(store, before);
        } finally {
            acknowledgeClose.set(true);
            field.set(executor, worker);
        }
        RetainedCopyRecord interrupted = store.read(before.owner(), before.copyId());
        assertThat(interrupted.command()).isNotNull();
        assertThat(interrupted.acknowledged().id())
                .isEqualTo(before.acknowledged().id());
        try (var released = store.writer(before.owner(), before.copyId())) {
            assertThat(released).isNotNull();
        }
        return interrupted;
    }

    private static WorkerClient lostCloseAcknowledgement(WorkerClient worker, AtomicBoolean acknowledgeClose) {
        WorkerClient intercepted = spy(worker);
        doAnswer(call -> {
                    JsonNode reply = worker.request(
                            call.getArgument(0),
                            call.getArgument(1),
                            call.getArgument(2),
                            call.getArgument(3),
                            call.getArgument(4));
                    if (!acknowledgeClose.get()) {
                        throw new WorkerUnavailableException();
                    }
                    return reply;
                })
                .when(intercepted)
                .request(any(), any(), eq("CLOSE"), any(), any());
        return intercepted;
    }

    private static void assertWriterBusy(RetainedCopyStore store, RetainedCopyRecord record) {
        assertThatThrownBy(() -> {
                    try (var incorrectlyReleased = store.writer(record.owner(), record.copyId())) {
                        throw new AssertionError("An unconfirmed close released the retained writer lock");
                    }
                })
                .isInstanceOf(RetainedCopyException.class)
                .hasMessageContaining("BUSY");
    }

    private static void awaitWriterRelease(RetainedCopyStore store, RetainedCopyRecord record) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (true) {
            try (var released = store.writer(record.owner(), record.copyId())) {
                assertThat(released).isNotNull();
                return;
            } catch (RetainedCopyException busy) {
                assertThat(busy.reason()).isEqualTo(RetainedCopyException.Reason.BUSY);
            }
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private void awaitRunning(WorkerClient worker, RetainedCopyRecord record) throws Exception {
        WorkerClient.Hello hello = worker.retainedHello();
        var identity = new WorkerClient.Identity(
                actor.subjectId(),
                actor.accountId(),
                workspace.value(),
                record.transportHash(),
                record.writer().leaseId());
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (true) {
            JsonNode reply =
                    worker.request(hello, identity, "RENEW", new WorkerRequests.Renew(), Duration.ofSeconds(3));
            assertThat(reply.path("ok").asBoolean()).as(reply.toString()).isTrue();
            if (reply.path("state").asString().equals("RUNNING")) {
                return;
            }
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private void restoreBytes(WorkerClient worker, RetainedCopyRecord record) throws Exception {
        WorkerClient.Hello hello = worker.retainedHello();
        var identity = new WorkerClient.Identity(
                actor.subjectId(), actor.accountId(), workspace.value(), "f".repeat(64), UUID.randomUUID());
        RetainedCopyRecord.Checkpoint point = record.acknowledged();
        String commit = point.state().originalCommit();
        try {
            JsonNode restored = worker.request(
                    hello,
                    identity,
                    "RESTORE",
                    new WorkerRequests.Restore(
                            point.id().toString(),
                            point.sha256(),
                            point.bytes(),
                            commit,
                            "full",
                            record.writer().leaseId().toString()),
                    Duration.ofSeconds(15));
            awaitReady(worker, hello, identity, restored);
            JsonNode result = worker.request(
                    hello,
                    identity,
                    "EXEC",
                    new WorkerRequests.Exec(
                            UUID.randomUUID().toString(),
                            commit,
                            "set -eu; test \"$(git rev-parse HEAD)\" = " + commit + "; "
                                    + "test \"$(cat public/article.md)\" = 'acknowledged text'; "
                                    + "test \"$(cat private/draft.bin)\" = 'unsaved binary'; "
                                    + "test ! -e private/unconfirmed.bin; "
                                    + "python3 -c \"import json; d=json.load(open('.poketto/assets.json')); "
                                    + "assert 'private/draft.dat' in d['files']\"",
                            10000),
                    Duration.ofSeconds(15));
            assertThat(result.path("ok").asBoolean()).as(result.toString()).isTrue();
            assertThat(result.path("result").path("exitCode").asInt())
                    .as(result.toString())
                    .isZero();
        } finally {
            JsonNode closed = worker.request(
                    hello, identity, "CLOSE", new WorkerRequests.Close("client_shutdown"), Duration.ofSeconds(10));
            assertThat(closed.path("state").asString()).isEqualTo("CLOSED");
        }
    }

    private static void awaitReady(
            WorkerClient worker, WorkerClient.Hello hello, WorkerClient.Identity identity, JsonNode initial)
            throws Exception {
        JsonNode reply = initial;
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (true) {
            assertThat(reply.path("ok").asBoolean()).as(reply.toString()).isTrue();
            if (reply.path("state").asString().equals("READY")) {
                return;
            }
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(50);
            reply = worker.request(hello, identity, "RENEW", new WorkerRequests.Renew(), Duration.ofSeconds(3));
        }
    }

    private static final class Cancellation implements ExecutionCancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Runnable> callback = new AtomicReference<>();

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public Registration onCancel(Runnable terminate) {
            callback.set(terminate);
            if (cancelled.get()) {
                terminate.run();
            }
            return () -> callback.compareAndSet(terminate, null);
        }

        void cancel() {
            cancelled.set(true);
            Runnable terminate = callback.get();
            if (terminate != null) {
                terminate.run();
            }
        }
    }
}
