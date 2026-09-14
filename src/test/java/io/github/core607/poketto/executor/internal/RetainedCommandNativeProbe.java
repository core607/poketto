package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PortableContentExports;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.executor.internal.RetainedLifecycleNativeProbe.Control;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.mcp.SessionReplacedException;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real adapter command checkpoints and explicit recovery across adapter and transport replacement. */
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
    private final Map<RetainedCopyStore, RetainedWorkStores> stores = new IdentityHashMap<>();

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

    void run(Control control, AtomicBoolean privateRead) throws Exception {
        Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var store = new RetainedCopyStore(
                root.resolve("records"),
                new RetainedCopyStore.Limits(8, 8 * 1024 * 1024, 64 * 1024 * 1024, 0, Duration.ofMinutes(10)),
                Clock.systemUTC());
        try (var fixture = new PublicExecutionNativeFixture(root.resolve("repository"), exports, auth, workspace)) {
            try (var executor = adapter(fixture, store)) {
                RetainedCopyRecord record = commands(executor, store);
                assertOriginal(store, record);
                Field workerField = IsolatedRepositoryExecutor.class.getDeclaredField("worker");
                workerField.setAccessible(true);
                WorkerClient worker = (WorkerClient) workerField.get(executor);
                record = cancelAfterCheckpoint(executor, store, worker, record);
                executor.close();
                restoreAdapter(fixture, store, record);
            }
            expireAndReclaim(fixture);
            new RetainedOriginalNativeProbe(auth, actor, workspace, CANCELLATION)
                    .run(root, fixture, (records, reader) -> adapter(fixture, records, reader));
            restoreMovedText(fixture);
            new RetainedNonTextNativeProbe(auth, actor, workspace, CANCELLATION)
                    .run(root, fixture, (records, reader) -> adapter(fixture, records, reader));
            new RetainedDiscardNativeProbe(auth, actor, workspace, CANCELLATION)
                    .run(root, fixture, (records, reader) -> adapter(fixture, records, reader));
            new RetainedContentionNativeProbe(auth, actor, workspace, CANCELLATION)
                    .run(root, fixture, (records, reader) -> adapter(fixture, records, reader));
            new RetainedLifecycleNativeProbe(auth, actor, workspace, CANCELLATION)
                    .run(root, fixture, (records, reader) -> adapter(fixture, records, reader), control, privateRead);
        }
    }

    private IsolatedRepositoryExecutor adapter(PublicExecutionNativeFixture fixture, RetainedCopyStore store) {
        return adapter(fixture, store, fixture.reader(auth));
    }

    private RetainedWorkStores stores(RetainedCopyStore store) {
        return stores.computeIfAbsent(
                store,
                records -> RetainedBaselineTestData.stores(records, root.resolve("originals-" + UUID.randomUUID())));
    }

    private void assertOriginal(RetainedCopyStore store, RetainedCopyRecord record) throws Exception {
        var originals = stores(store).originals();
        try (var writer = store.writer(record.owner(), record.copyId());
                var reader = originals.open(writer, record.originalBaseline())) {
            assertThat(reader.find("private/secret.md").orElseThrow().source()).contains("current-secret-needle");
            assertThat(reader.find("private/draft.bin")).isEmpty();
            assertThat(record.originalBaseline().identity().commit())
                    .isEqualTo(record.acknowledged().state().originalCommit());
        }
    }

    private IsolatedRepositoryExecutor adapter(
            PublicExecutionNativeFixture fixture, RetainedCopyStore store, AuthorizedRepositoryReader reader) {
        return new ExecutorConfiguration()
                .isolatedRepositoryExecutor(
                        Optional.of(stores(store)),
                        auth,
                        fixture.exports(),
                        mock(PortableContentExports.class),
                        fixture.media(auth),
                        reader,
                        fixture.patches(auth),
                        fixture.moves(auth),
                        json,
                        socket,
                        key,
                        4,
                        45,
                        8);
    }

    private void restoreMovedText(PublicExecutionNativeFixture fixture) {
        var store = new RetainedCopyStore(
                root.resolve("move-records"),
                new RetainedCopyStore.Limits(2, 8 * 1024 * 1024, 64 * 1024 * 1024, 0, Duration.ofMinutes(10)),
                Clock.systemUTC());
        RetainedCopyRecord record;
        try (var executor = adapter(fixture, store)) {
            var result = recoveredCommand(
                    executor,
                    "move-native",
                    new RepositoryExecutor.CopyRequest("new", null, false),
                    "set -eu; poketto move private/secret.md private/moved.md; "
                            + "test ! -e private/secret.md; test \"$(cat private/moved.md)\" = current-secret-needle");
            record = store.read(
                    new RetainedCopyRecord.Owner(actor.subjectId(), workspace.value()),
                    UUID.fromString(result.copyId()));
            assertThat(record.acknowledged()
                            .state()
                            .fileBaselines()
                            .get("private/moved.md")
                            .source())
                    .isEqualTo("current-secret-needle");
            assertThat(record.acknowledged()
                            .state()
                            .fileBaselines()
                            .get("private/secret.md")
                            .source())
                    .isNull();
        }
        var reader = spy(fixture.reader(auth));
        doThrow(new ContentRepositoryException("historical moved text unavailable"))
                .when(reader)
                .getFile(
                        eq(actor),
                        eq(workspace),
                        eq(Optional.of(record.acknowledged().state().baseCommit())),
                        eq("private/moved.md"));
        try (var executor = adapter(fixture, store, reader)) {
            recoveredCommand(
                    executor,
                    "move-resumed",
                    new RepositoryExecutor.CopyRequest(record.copyId().toString(), 1L, true),
                    "set -eu; test ! -e private/secret.md; "
                            + "test \"$(cat private/moved.md)\" = current-secret-needle; "
                            + "printf 'after native recovery' > private/moved.md; poketto save private/moved.md");
            var after = store.read(record.owner(), record.copyId());
            assertThat(after.acknowledged().state().originalCommit())
                    .isEqualTo(record.acknowledged().state().originalCommit());
            assertThat(after.acknowledged()
                            .state()
                            .fileBaselines()
                            .get("private/moved.md")
                            .source())
                    .isEqualTo("after native recovery");
        }
    }

    private void expireAndReclaim(PublicExecutionNativeFixture fixture) throws Exception {
        var store = new RetainedCopyStore(
                root.resolve("expiring-records"),
                new RetainedCopyStore.Limits(2, 8 * 1024 * 1024, 64 * 1024 * 1024, 0, Duration.ofSeconds(8)),
                Clock.systemUTC());
        var meters = new SimpleMeterRegistry();
        try (var executor = adapter(fixture, store);
                var maintenance = new RetainedCopyMaintenance(store, Duration.ofMillis(200))) {
            executor.bindMetrics(meters);
            var fresh = new RepositoryExecutor.CopyRequest("new", null, false);
            RepositoryExecutor.ExecutionResult result = recoveredCommand(
                    executor, "expiry-native", fresh, "printf retained-until-expiry > private/expiring");
            var owner = new RetainedCopyRecord.Owner(actor.subjectId(), workspace.value());
            RetainedCopyRecord record = store.read(owner, UUID.fromString(result.copyId()));
            Files.writeString(
                    root.resolve("expired-checkpoint"),
                    record.acknowledged().id().toString());
            assertThat(System.currentTimeMillis()).isLessThan(result.retention().expiresAt());
            awaitExpired(store, record, meters);
            RepositoryExecutor.ExecutionResult replacement =
                    recoveredCommand(executor, "expiry-native", fresh, "test ! -e private/expiring");
            assertThat(replacement.copyId()).isNotEqualTo(result.copyId());
        }
    }

    private static void awaitExpired(RetainedCopyStore store, RetainedCopyRecord record, SimpleMeterRegistry meters)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (true) {
            boolean missing = false;
            try {
                store.read(record.owner(), record.copyId());
            } catch (RetainedCopyException failure) {
                assertThat(failure.reason())
                        .isIn(
                                RetainedCopyException.Reason.EXPIRED,
                                RetainedCopyException.Reason.MISSING,
                                RetainedCopyException.Reason.BUSY);
                missing = failure.reason() == RetainedCopyException.Reason.MISSING;
            }
            if (missing
                    && meters.get("poketto.executor.sessions.active").gauge().value() == 0) {
                return;
            }
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(50);
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
        assertThat(first.acknowledged()
                        .state()
                        .fileBaselines()
                        .get("public/article.md")
                        .source())
                .isEqualTo("acknowledged text");
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

    static WorkerClient lostCloseAcknowledgement(WorkerClient worker, AtomicBoolean acknowledgeClose) {
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

    static void assertWriterBusy(RetainedCopyStore store, RetainedCopyRecord record) {
        assertThatThrownBy(() -> {
                    try (var incorrectlyReleased = store.writer(record.owner(), record.copyId())) {
                        throw new AssertionError("An unconfirmed close released the retained writer lock");
                    }
                })
                .isInstanceOf(RetainedCopyException.class)
                .hasMessageContaining("BUSY");
    }

    static void awaitWriterRelease(RetainedCopyStore store, RetainedCopyRecord record)
            throws IOException, InterruptedException {
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

    private void restoreAdapter(
            PublicExecutionNativeFixture fixture, RetainedCopyStore store, RetainedCopyRecord record) {
        try (var restored = adapter(fixture, store)) {
            var expected = new RepositoryExecutor.CopyRequest(record.copyId().toString(), record.generation(), true);
            RepositoryExecutor.ExecutionResult result = recoveredCommand(
                    restored,
                    "resumed-chat",
                    expected,
                    "set -eu; test \"$(git rev-parse HEAD)\" = "
                            + record.acknowledged().state().originalCommit() + "; "
                            + "test \"$(cat public/article.md)\" = 'acknowledged text'; "
                            + "test \"$(cat private/draft.bin)\" = 'unsaved binary'; "
                            + "test ! -e private/unconfirmed.bin; "
                            + "python3 -c \"import json; assert 'private/draft.dat' in json.load(open('.poketto/assets.json'))['files']\"; "
                            + "poketto status");
            assertThat(result.exitCode())
                    .as("%s %s", result.stdout(), result.stderr())
                    .isZero();
            assertThat(result.copyId()).isEqualTo(record.copyId().toString());
            assertThat(result.retention().generation()).isEqualTo(2);
            assertThat(result.retention().resumed()).isTrue();
            assertThat(result.retention().lastInterruptedCommand())
                    .isEqualTo(record.command().id());
            RetainedCopyRecord after = store.read(record.owner(), record.copyId());
            assertThat(after.originalBaseline()).isEqualTo(record.originalBaseline());
            assertThat(after.acknowledged().state().baseCommit())
                    .isEqualTo(record.acknowledged().state().baseCommit());
            assertThat(after.acknowledged().state().fileBaselines())
                    .isEqualTo(record.acknowledged().state().fileBaselines());
            assertThat(json.writeValueAsString(after.acknowledged().state().lastImport()))
                    .isEqualTo(json.writeValueAsString(
                            record.acknowledged().state().lastImport()));
            assertThatThrownBy(() -> recoveredCommand(restored, "stale-chat", expected, "touch private/replayed"))
                    .isInstanceOfSatisfying(
                            ExecutionAdmissionException.class,
                            failure -> assertThat(failure.reason())
                                    .isEqualTo(ExecutionAdmissionException.Reason.GENERATION_MISMATCH));
            RepositoryExecutor.ExecutionResult next = recoveredCommand(
                    restored,
                    "next-chat",
                    new RepositoryExecutor.CopyRequest(result.copyId(), 2L, true),
                    "set -eu; test ! -e private/replayed; test -f private/draft.bin");
            assertThat(next.exitCode())
                    .as("%s %s", next.stdout(), next.stderr())
                    .isZero();
            assertThat(next.retention().generation()).isEqualTo(3);
            assertThat(next.retention().lastInterruptedCommand())
                    .isEqualTo(record.command().id());
            parallelChats(restored, store, next);
        }
    }

    private void parallelChats(
            IsolatedRepositoryExecutor executor, RetainedCopyStore store, RepositoryExecutor.ExecutionResult original) {
        RepositoryExecutor.ExecutionResult other = recoveredCommand(
                executor,
                "other-chat",
                new RepositoryExecutor.CopyRequest("new", null, false),
                "printf other > private/other-chat");
        assertThat(other.exitCode()).isZero();
        other = restoreReplyLoss(executor, store, other);
        assertThat(other.copyId()).isNotEqualTo(original.copyId());
        var resume = new RepositoryExecutor.CopyRequest(
                original.copyId(), original.retention().generation(), true);
        assertThatThrownBy(() -> recoveredCommand(executor, "other-chat", resume, "touch private/wrong-chat"))
                .isInstanceOf(SessionReplacedException.class);
        RepositoryExecutor.ExecutionResult preserved = recoveredCommand(
                executor,
                "other-chat",
                new RepositoryExecutor.CopyRequest(
                        other.copyId(), other.retention().generation(), false),
                "set -eu; test ! -e private/wrong-chat; test ! -e private/draft.bin; test -f private/other-chat");
        assertThat(preserved.exitCode())
                .as("%s %s", preserved.stdout(), preserved.stderr())
                .isZero();
        var first = CompletableFuture.supplyAsync(() -> racingRecovery(executor, "race-left", resume));
        var second = CompletableFuture.supplyAsync(() -> racingRecovery(executor, "race-right", resume));
        Object left = first.join();
        Object right = second.join();
        assertThat(left instanceof RepositoryExecutor.ExecutionResult
                        ^ right instanceof RepositoryExecutor.ExecutionResult)
                .isTrue();
        Object loser = left instanceof RepositoryExecutor.ExecutionResult ? right : left;
        assertThat(loser).isInstanceOf(ExecutionAdmissionException.class);
        assertThat(((ExecutionAdmissionException) loser).reason())
                .isIn(ExecutionAdmissionException.Reason.BUSY, ExecutionAdmissionException.Reason.GENERATION_MISMATCH);
        RepositoryExecutor.ExecutionResult recovered = recoveredCommand(
                executor,
                "after-race",
                new RepositoryExecutor.CopyRequest(
                        original.copyId(), original.retention().generation() + 1, true),
                "set -eu; test ! -e private/other-chat; test -f private/draft.bin; test \"$(cat private/race)\" = once");
        assertThat(recovered.exitCode())
                .as("%s %s", recovered.stdout(), recovered.stderr())
                .isZero();
    }

    private RepositoryExecutor.ExecutionResult restoreReplyLoss(
            IsolatedRepositoryExecutor executor, RetainedCopyStore store, RepositoryExecutor.ExecutionResult original) {
        var owner = new RetainedCopyRecord.Owner(actor.subjectId(), workspace.value());
        RetainedCopyRecord record = store.read(owner, UUID.fromString(original.copyId()));
        var acknowledgeClose = new AtomicBoolean();
        try {
            Field field = IsolatedRepositoryExecutor.class.getDeclaredField("worker");
            field.setAccessible(true);
            WorkerClient worker = (WorkerClient) field.get(executor);
            field.set(executor, lostRestoreReplies(worker, acknowledgeClose));
            try {
                assertThatThrownBy(() -> recoveredCommand(
                                executor,
                                "lost-restore",
                                new RepositoryExecutor.CopyRequest(
                                        original.copyId(), original.retention().generation(), true),
                                "touch private/not-started"))
                        .isInstanceOfSatisfying(
                                ExecutionAdmissionException.class,
                                failure -> assertThat(failure.reason())
                                        .isEqualTo(ExecutionAdmissionException.Reason.UNAVAILABLE));
                assertWriterBusy(store, record);
                acknowledgeClose.set(true);
                awaitWriterRelease(store, record);
            } finally {
                acknowledgeClose.set(true);
                field.set(executor, worker);
            }
        } catch (ReflectiveOperationException | IOException | InterruptedException failure) {
            throw new AssertionError("Lost restoration reply did not reconcile", failure);
        }
        RetainedCopyRecord transferred = store.read(owner, record.copyId());
        return recoveredCommand(
                executor,
                "other-chat",
                new RepositoryExecutor.CopyRequest(original.copyId(), transferred.generation(), true),
                "set -eu; test ! -e private/not-started; test -f private/other-chat");
    }

    private WorkerClient lostRestoreReplies(WorkerClient worker, AtomicBoolean acknowledgeClose) {
        WorkerClient intercepted = spy(worker);
        var restoring = new AtomicBoolean();
        doAnswer(call -> {
                    WorkerClient.PreparedRequest request = call.getArgument(0);
                    JsonNode payload = json.readTree(
                            Base64.getUrlDecoder().decode(request.envelope().payload()));
                    JsonNode reply = worker.send(request, call.getArgument(1));
                    String operation = payload.path("operation").stringValue();
                    if (operation.equals("RESTORE")) {
                        restoring.set(true);
                        throw new WorkerUnavailableException();
                    }
                    if (operation.equals("CLOSE") && restoring.get() && !acknowledgeClose.get()) {
                        throw new WorkerUnavailableException();
                    }
                    return reply;
                })
                .when(intercepted)
                .send(any(), any());
        return intercepted;
    }

    private Object racingRecovery(
            IsolatedRepositoryExecutor executor, String session, RepositoryExecutor.CopyRequest copy) {
        try {
            return recoveredCommand(executor, session, copy, "set -eu; printf once >> private/race; sleep 1");
        } catch (ExecutionAdmissionException refused) {
            return refused;
        }
    }

    private RepositoryExecutor.ExecutionResult recoveredCommand(
            IsolatedRepositoryExecutor executor,
            String session,
            RepositoryExecutor.CopyRequest expected,
            String command) {
        RepositoryExecutor.ExecutionResult result = executor.execute(
                actor, workspace, session, expected, Optional.empty(), command, Duration.ofSeconds(30), CANCELLATION);
        assertThat(result.exitCode())
                .as("%s %s", result.stdout(), result.stderr())
                .isZero();
        return result;
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
