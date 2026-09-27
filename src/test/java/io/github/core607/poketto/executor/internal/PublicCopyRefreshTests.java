package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.assets.MediaFileService;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PortableContentExports;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.DataOutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A public copy whose publication changed, driven through the executor's entry point against a
 * socket peer that keeps each copy's pinned commit, so a command that reached a stale copy would be
 * visible. Frame signing is covered by WorkerSocketTests and sandboxing by the native probes.
 */
class PublicCopyRefreshTests {
    private static final WorkspaceId WORKSPACE = WorkspaceId.random();
    private static final String FIRST = "1".repeat(40);
    private static final String SECOND = "2".repeat(40);
    private static final String THIRD = "3".repeat(40);
    private static final ExecutionCancellation NOT_CANCELLED = new ExecutionCancellation() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public Registration onCancel(Runnable terminate) {
            return () -> {};
        }
    };

    private final AtomicReference<RepositorySnapshotExports.PublicExport> published = new AtomicReference<>();
    private final RepositorySnapshotExports exports = mock(RepositorySnapshotExports.class);
    private final AuthService auth = mock(AuthService.class);
    private final AuthPrincipal principal = mock(AuthPrincipal.class);
    private final AccountCopyRecord.Owner owner;
    private final AtomicBoolean revoked = new AtomicBoolean();

    PublicCopyRefreshTests() {
        when(principal.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(principal.subjectId()).thenReturn(UUID.randomUUID());
        when(principal.accountId()).thenReturn(UUID.randomUUID());
        owner = new AccountCopyRecord.Owner(principal.accountId(), WORKSPACE.value(), false);
        when(auth.authorize(any(), any(), eq(Capability.EXECUTE_REPOSITORY))).thenAnswer(call -> {
            if (revoked.get()) {
                throw new SecurityException("execution grant revoked");
            }
            return new WorkspaceAccess(
                    WORKSPACE, call.getArgument(0), MembershipRole.MEMBER, Set.of(Capability.EXECUTE_REPOSITORY));
        });
        // Exports and validity follow the current publication, which each test replaces to change it.
        when(exports.createPublic(any(), eq(WORKSPACE))).thenAnswer(call -> published.get());
        doAnswer(call -> !published.get().equals(call.getArgument(2)))
                .when(exports)
                .publicProjectionChanged(any(), eq(WORKSPACE), any());
        doAnswer(call -> {
                    if (!published.get().equals(call.getArgument(2))) {
                        throw new ContentRepositoryException("public projection changed");
                    }
                    return null;
                })
                .when(exports)
                .requireCurrentPublic(any(), eq(WORKSPACE), any());
        publish(FIRST);
    }

    @Test
    void aCleanStaleCopyIsRebuiltUnderItsIdBeforeTheCommandRuns() throws Exception {
        try (var worker = new Worker();
                var executor = executor(worker)) {
            var first = run(executor, "new", "ls");
            assertThat(first.refreshed()).isFalse();
            var changed = publish(SECOND);

            var refreshed = run(executor, first.copyId(), "cat article/index.md");

            assertThat(refreshed.copyId()).isEqualTo(first.copyId());
            assertThat(refreshed.refreshed()).isTrue();
            assertThat(refreshed.commit()).isEqualTo(SECOND);
            assertThat(refreshed.freshSandbox()).isTrue();
            assertThat(worker.sequence())
                    .containsExactly("OPEN", "EXEC", "CLOSE", "ATTACH", "EXEC", "CLOSE", "DISCARD", "OPEN", "EXEC");
            var commands = worker.commands();
            assertThat(commands)
                    .extracting(Worker.Command::commit, Worker.Command::command)
                    .containsExactly(
                            tuple(FIRST, "ls"),
                            tuple(FIRST, PublicCopyRefresh.inspection(FIRST)),
                            tuple(SECOND, "cat article/index.md"));
            assertThat(commands.get(1).lease()).isNotEqualTo(commands.get(0).lease());
            assertThat(commands.get(1).fresh()).isTrue();
            assertThat(worker.copies).containsExactly(entry(first.copyId(), SECOND));
            verify(exports, times(2)).createPublic(principal, WORKSPACE);
            verify(exports, atLeastOnce()).requireCurrentPublic(principal, WORKSPACE, changed);
            assertThat(worker.accounts.record(owner).orElseThrow())
                    .satisfies(record -> assertThat(record.phase()).isEqualTo(AccountCopyRecord.Phase.READY))
                    .satisfies(record -> assertThat(record.publicExport()).isEqualTo(changed));

            var next = run(executor, first.copyId(), "pwd");
            assertThat(next.refreshed()).isFalse();
            assertThat(next.copyId()).isEqualTo(first.copyId());
            assertThat(worker.operations("OPEN")).hasSize(2);
        }
    }

    @Test
    void aStaleCopyWithLocalChangesIsRefusedAndLeftUntouched() throws Exception {
        try (var worker = new Worker()) {
            String copy;
            try (var executor = executor(worker)) {
                copy = run(executor, "new", "touch notes.md").copyId();
            }
            var opened = worker.accounts.record(owner).orElseThrow();
            publish(SECOND);
            worker.dirty = true;
            try (var executor = executor(worker)) {
                assertPublicationChanged(() -> run(executor, copy, "cat notes.md"), copy);
                assertPublicationChanged(() -> run(executor, "new", "pwd"), copy);
            }
            assertThat(worker.copies).containsExactly(entry(copy, FIRST));
            assertThat(worker.operations("DISCARD")).isEmpty();
            assertThat(worker.commands())
                    .extracting(Worker.Command::command)
                    .containsExactly(
                            "touch notes.md", PublicCopyRefresh.inspection(FIRST), PublicCopyRefresh.inspection(FIRST));
            verify(exports, times(1)).createPublic(any(), any());
            var kept = worker.accounts.record(owner).orElseThrow();
            assertThat(kept.phase()).isEqualTo(AccountCopyRecord.Phase.READY);
            assertThat(kept.publicExport()).isEqualTo(opened.publicExport());
            assertThat(kept.state()).isEqualTo(opened.state());
        }
    }

    @Test
    void pendingOperationsCountAsUnsavedWithoutRunningTheInspection() throws Exception {
        try (var worker = new Worker();
                var executor = executor(worker)) {
            String copy = run(executor, "new", "ls").copyId();
            worker.accounts.rewrite(owner, record -> withPendingSync(record));
            publish(SECOND);
            assertPublicationChanged(() -> run(executor, copy, "pwd"), copy);
            assertThat(worker.sequence()).containsExactly("OPEN", "EXEC", "CLOSE");
            assertThat(worker.copies).containsExactly(entry(copy, FIRST));
        }
        try (var worker = new Worker()) {
            String copy;
            try (var executor = executor(worker)) {
                copy = run(executor, "new", "ls").copyId();
            }
            publish(THIRD);
            // The worker's installed baseline differs from the journal's: a local baseline update is pending.
            worker.attachedGitCommit = "9".repeat(40);
            try (var executor = executor(worker)) {
                assertPublicationChanged(() -> run(executor, copy, "pwd"), copy);
            }
            assertThat(worker.commands()).extracting(Worker.Command::command).containsExactly("ls");
            assertThat(worker.operations("DISCARD")).isEmpty();
        }
    }

    @Test
    void otherPublicationFailuresKeepRefusingWithoutInspectingOrRebuilding() throws Exception {
        try (var worker = new Worker();
                var executor = executor(worker)) {
            String copy = run(executor, "new", "ls").copyId();
            doThrow(new ContentRepositoryException("publication snapshot unavailable"))
                    .when(exports)
                    .publicProjectionChanged(any(), eq(WORKSPACE), any());

            assertThatThrownBy(() -> run(executor, copy, "pwd")).isInstanceOf(ContentRepositoryException.class);

            assertThat(worker.sequence()).containsExactly("OPEN", "EXEC", "CLOSE");
            doAnswer(call -> !published.get().equals(call.getArgument(2)))
                    .when(exports)
                    .publicProjectionChanged(any(), eq(WORKSPACE), any());
            var resumed = run(executor, copy, "pwd");
            assertThat(resumed.refreshed()).isFalse();
            assertThat(resumed.commit()).isEqualTo(FIRST);
            assertThat(worker.operations("DISCARD")).isEmpty();
            verify(exports, times(1)).createPublic(any(), any());
        }
    }

    @Test
    void concurrentRequestsForOneStaleCopyRebuildItOnce() throws Exception {
        try (var callers = Executors.newVirtualThreadPerTaskExecutor();
                var worker = new Worker();
                var executor = executor(worker)) {
            String copy = run(executor, "new", "ls").copyId();
            publish(SECOND);
            worker.holdInspection = true;
            var first = callers.submit(() -> run(executor, copy, "echo first"));
            assertThat(worker.inspectionEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = callers.submit(() -> run(executor, copy, "echo second"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (worker.accounts.contended() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(worker.accounts.contended()).isPositive();
            worker.inspectionRelease.countDown();

            var results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(results)
                    .extracting(RepositoryExecutor.ExecutionResult::copyId)
                    .containsOnly(copy);
            assertThat(results)
                    .extracting(RepositoryExecutor.ExecutionResult::commit)
                    .containsOnly(SECOND);
            assertThat(results)
                    .extracting(RepositoryExecutor.ExecutionResult::refreshed)
                    .containsExactly(true, false);
            assertThat(worker.operations("DISCARD")).hasSize(1);
            assertThat(worker.operations("OPEN")).hasSize(2);
            assertThat(worker.commands())
                    .extracting(Worker.Command::command)
                    .containsExactly("ls", PublicCopyRefresh.inspection(FIRST), "echo first", "echo second");
        }
    }

    @Test
    void anInterruptedRebuildResumesUnderTheSameIdOnTheNextRequest() throws Exception {
        try (var worker = new Worker()) {
            String copy;
            try (var executor = executor(worker)) {
                copy = run(executor, "new", "ls").copyId();
            }
            var second = publish(SECOND);
            // The first rebuild stops after the worker discarded the files, before the new export exists.
            when(exports.createPublic(any(), eq(WORKSPACE)))
                    .thenThrow(new ContentRepositoryException("export interrupted"))
                    .thenAnswer(call -> published.get());
            try (var executor = executor(worker)) {
                assertThatThrownBy(() -> run(executor, copy, "pwd")).isInstanceOf(ContentRepositoryException.class);
            }
            assertThat(worker.copies).isEmpty();
            assertThat(worker.accounts.record(owner).orElseThrow().phase())
                    .isEqualTo(AccountCopyRecord.Phase.REFRESHING);

            // The next rebuild stops after the worker created the new copy but before it was ready.
            worker.failNextOpen = true;
            try (var executor = executor(worker)) {
                assertThatThrownBy(() -> run(executor, copy, "pwd")).isInstanceOf(ExecutionAdmissionException.class);
            }
            assertThat(worker.copies).containsExactly(entry(copy, SECOND));
            var interrupted = worker.accounts.record(owner).orElseThrow();
            assertThat(interrupted.phase()).isEqualTo(AccountCopyRecord.Phase.REFRESHING);
            assertThat(interrupted.copyId().toString()).isEqualTo(copy);
            assertThat(interrupted.publicExport()).isEqualTo(second);

            publish(THIRD);
            try (var executor = executor(worker)) {
                var resumed = run(executor, copy, "cat article/index.md");
                assertThat(resumed.copyId()).isEqualTo(copy);
                assertThat(resumed.refreshed()).isTrue();
                assertThat(resumed.commit()).isEqualTo(THIRD);
            }
            assertThat(worker.copies).containsExactly(entry(copy, THIRD));
            assertThat(worker.discarded).containsExactly("DISCARDED", "ABSENT", "DISCARDED");
            // Each rebuild re-pinned the record in one write, so the copy ID never left the journal.
            assertThat(worker.accounts.removed()).isZero();
            assertThat(worker.commands())
                    .extracting(Worker.Command::commit, Worker.Command::command)
                    .containsExactly(
                            tuple(FIRST, "ls"),
                            tuple(FIRST, PublicCopyRefresh.inspection(FIRST)),
                            tuple(THIRD, "cat article/index.md"));
        }
    }

    @Test
    void authorizationLostDuringTheInspectionIsDeniedBeforeAnythingIsDiscarded() throws Exception {
        try (var worker = new Worker();
                var executor = executor(worker)) {
            String copy = run(executor, "new", "ls").copyId();
            publish(SECOND);
            worker.onInspection = () -> revoked.set(true);

            assertThatThrownBy(() -> run(executor, copy, "pwd")).isInstanceOf(SecurityException.class);

            assertThat(worker.operations("DISCARD")).isEmpty();
            assertThat(worker.copies).containsExactly(entry(copy, FIRST));
            assertThat(worker.accounts.record(owner).orElseThrow().phase()).isEqualTo(AccountCopyRecord.Phase.READY);
            verify(exports, times(1)).createPublic(any(), any());
        }
    }

    @Test
    void aWorkerCapacityRefusalOfTheInspectionIsReportedAsCapacity() throws Exception {
        try (var worker = new Worker();
                var executor = executor(worker)) {
            String copy = run(executor, "new", "ls").copyId();
            publish(SECOND);
            worker.inspectionCapacity = true;

            assertThatThrownBy(() -> run(executor, copy, "pwd"))
                    .isInstanceOfSatisfying(ExecutionAdmissionException.class, refused -> {
                        assertThat(refused.reason()).isEqualTo(ExecutionAdmissionException.Reason.CAPACITY);
                        assertThat(refused.recoveryAvailable()).isTrue();
                    });

            assertThat(worker.operations("DISCARD")).isEmpty();
            assertThat(worker.copies).containsExactly(entry(copy, FIRST));
            worker.inspectionCapacity = false;
            var refreshed = run(executor, copy, "pwd");
            assertThat(refreshed.refreshed()).isTrue();
            assertThat(refreshed.copyId()).isEqualTo(copy);
        }
    }

    @Test
    void theRebuiltCopyKeepsTheAdmissionSlotWhileTheWorkerDiscards() throws Exception {
        var other = otherAccount();
        try (var callers = Executors.newVirtualThreadPerTaskExecutor();
                var worker = new Worker();
                var executor = executor(worker, 1)) {
            String copy = run(executor, "new", "ls").copyId();
            publish(SECOND);
            worker.holdDiscard = true;
            var refreshing = callers.submit(() -> run(executor, copy, "pwd"));
            assertThat(worker.discardEntered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> run(executor, other, "new", "pwd"))
                    .isInstanceOfSatisfying(
                            ExecutionAdmissionException.class,
                            refused -> assertThat(refused.reason())
                                    .isEqualTo(ExecutionAdmissionException.Reason.CAPACITY));

            worker.discardRelease.countDown();
            var refreshed = refreshing.get(10, TimeUnit.SECONDS);
            assertThat(refreshed.refreshed()).isTrue();
            assertThat(worker.operations("OPEN")).hasSize(2);
        }
    }

    @Test
    void aSlotReleasedBeforeTheHandOverStopsTheRebuildBeforeAnythingIsDiscarded() throws Exception {
        var other = otherAccount();
        try (var worker = new Worker();
                var executor = executor(worker, 1)) {
            String copy = run(executor, "new", "ls").copyId();
            publish(SECOND);
            var cancellation = new Cancellation();
            var fired = new AtomicBoolean();
            var admitted = new AtomicReference<RepositoryExecutor.ExecutionResult>();
            // Once the clean copy is journaled for refresh, cancellation closes the inspection lease
            // and another account takes the slot that lease released.
            worker.accounts.afterWrite(record -> {
                if (record.owner().equals(owner)
                        && record.phase() == AccountCopyRecord.Phase.REFRESHING
                        && fired.compareAndSet(false, true)) {
                    cancellation.cancel();
                    admitted.set(run(executor, other, "new", "pwd"));
                }
            });

            assertThatThrownBy(() -> run(executor, principal, copy, "pwd", cancellation))
                    .isInstanceOfSatisfying(
                            ExecutionAdmissionException.class,
                            refused -> assertThat(refused.reason())
                                    .isEqualTo(ExecutionAdmissionException.Reason.UNAVAILABLE));

            assertThat(admitted.get().exitCode()).isZero();
            assertThat(worker.operations("DISCARD")).isEmpty();
            assertThat(worker.copies).containsEntry(copy, FIRST);
            assertThat(worker.accounts.record(owner).orElseThrow().phase())
                    .isEqualTo(AccountCopyRecord.Phase.REFRESHING);
            var resumed = run(executor, copy, "pwd");
            assertThat(resumed.refreshed()).isTrue();
            assertThat(resumed.copyId()).isEqualTo(copy);
            assertThat(resumed.commit()).isEqualTo(SECOND);
        }
    }

    @Test
    void theInspectionEmbedsOnlyAnExactCommit() {
        assertThatThrownBy(() -> PublicCopyRefresh.inspection("HEAD; rm -rf ."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertPublicationChanged(Runnable request, String copy) {
        assertThatThrownBy(request::run).isInstanceOfSatisfying(ExecutionAdmissionException.class, refused -> {
            assertThat(refused.reason()).isEqualTo(ExecutionAdmissionException.Reason.PUBLICATION_CHANGED);
            assertThat(refused.copyId()).contains(copy);
            assertThat(refused.recoveryAvailable()).isTrue();
        });
    }

    private static AccountCopyRecord withPendingSync(AccountCopyRecord record) {
        var state = record.state();
        var pending = new RetainedSaveState(
                state.originalCommit(),
                state.baseCommit(),
                state.baselines(),
                state.fileBaselines(),
                false,
                null,
                null,
                null,
                state.lastSave(),
                state.lastImport(),
                new PendingWorkspaceSync("9".repeat(40), List.of("article/index.md"), 0, List.of(), null));
        return new AccountCopyRecord(
                record.format(),
                record.owner(),
                record.copyId(),
                record.revision(),
                record.expiresAt(),
                record.writer(),
                record.phase(),
                record.executionId(),
                record.lastInterruptedCommand(),
                pending,
                record.publicExport(),
                record.original());
    }

    private RepositorySnapshotExports.PublicExport publish(String commit) {
        var export = new RepositorySnapshotExports.PublicExport(
                WORKSPACE,
                new RepositorySnapshotExports.Export(UUID.randomUUID(), commit, "b".repeat(64), 128),
                "c".repeat(40),
                commit.substring(0, 1).repeat(64),
                Map.of("article/index.md", "public/article.md"),
                Map.of());
        published.set(export);
        return export;
    }

    private IsolatedRepositoryExecutor executor(Worker worker) {
        return executor(worker, 8);
    }

    private IsolatedRepositoryExecutor executor(Worker worker, int maxSessions) {
        return new IsolatedRepositoryExecutor(
                worker.accounts.store(),
                mock(PortableContentExports.class),
                mock(MediaFileService.class),
                mock(SelectedFileSaves.class),
                auth,
                exports,
                worker.client(),
                maxSessions,
                Duration.ofSeconds(8),
                Duration.ofSeconds(3));
    }

    private RepositoryExecutor.ExecutionResult run(IsolatedRepositoryExecutor executor, String copy, String command) {
        return run(executor, principal, copy, command);
    }

    private static RepositoryExecutor.ExecutionResult run(
            IsolatedRepositoryExecutor executor, AuthPrincipal caller, String copy, String command) {
        return run(executor, caller, copy, command, NOT_CANCELLED);
    }

    private static RepositoryExecutor.ExecutionResult run(
            IsolatedRepositoryExecutor executor,
            AuthPrincipal caller,
            String copy,
            String command,
            ExecutionCancellation cancellation) {
        return executor.execute(
                caller,
                WORKSPACE,
                "transport",
                new RepositoryExecutor.CopyRequest(copy),
                Optional.empty(),
                command,
                Duration.ofSeconds(5),
                cancellation);
    }

    private static AuthPrincipal otherAccount() {
        var other = mock(AuthPrincipal.class);
        when(other.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(other.subjectId()).thenReturn(UUID.randomUUID());
        when(other.accountId()).thenReturn(UUID.randomUUID());
        return other;
    }

    /** Runs registered terminations on the thread that cancels, as the MCP cancellation does. */
    private static final class Cancellation implements ExecutionCancellation {
        private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();
        private volatile boolean cancelled;

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public Registration onCancel(Runnable terminate) {
            if (cancelled) {
                terminate.run();
                return () -> {};
            }
            callbacks.add(terminate);
            return () -> callbacks.remove(terminate);
        }

        void cancel() {
            cancelled = true;
            callbacks.forEach(Runnable::run);
        }
    }

    /** Keeps each disk copy's pinned commit and each lease's commit, as the worker's disk pool does. */
    private static final class Worker implements AutoCloseable {
        record Command(String lease, String commit, String command, boolean fresh) {}

        private final SocketAccountJournal accounts = new SocketAccountJournal();
        private final ObjectMapper json = new ObjectMapper();
        private final KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        private final UUID boot = UUID.randomUUID();
        private final Path path;
        private final ServerSocketChannel server;
        private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        private final List<Command> commands = new CopyOnWriteArrayList<>();
        private final List<String> discarded = new CopyOnWriteArrayList<>();
        private final Map<String, String> copies = new ConcurrentHashMap<>();
        private final Map<String, String> leaseCommits = new ConcurrentHashMap<>();
        private final Set<String> startedUnits = ConcurrentHashMap.newKeySet();
        private final CountDownLatch inspectionEntered = new CountDownLatch(1);
        private final CountDownLatch inspectionRelease = new CountDownLatch(1);
        private final CountDownLatch discardEntered = new CountDownLatch(1);
        private final CountDownLatch discardRelease = new CountDownLatch(1);
        private volatile boolean dirty;
        private volatile boolean holdInspection;
        private volatile boolean inspectionCapacity;
        private volatile boolean holdDiscard;
        private volatile boolean failNextOpen;
        private volatile String attachedGitCommit;
        private volatile Runnable onInspection = () -> {};

        Worker() throws Exception {
            Path directory = Path.of(".gradle", "uds").toAbsolutePath();
            Files.createDirectories(directory);
            path = directory.resolve("r-" + UUID.randomUUID().toString().substring(0, 8));
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(path));
            threads.submit(() -> {
                while (!closed.get()) {
                    try {
                        SocketChannel connection = server.accept();
                        threads.submit(() -> respond(connection));
                    } catch (Exception exception) {
                        if (!closed.get()) {
                            failure.compareAndSet(null, exception);
                        }
                    }
                }
            });
        }

        WorkerClient client() {
            return new WorkerClient(path, key.getPrivate(), () -> {}, channel -> {}, json, Clock.systemUTC());
        }

        List<JsonNode> operations(String operation) {
            return requests.stream()
                    .filter(node -> node.path("operation").asString("").equals(operation))
                    .toList();
        }

        /** Lifecycle operations in order; renewals and bridge polls depend on timing and are left out. */
        List<String> sequence() {
            return requests.stream()
                    .map(node -> node.path("operation").asString(""))
                    .filter(operation -> !Set.of("RENEW", "BRIDGE_POLL").contains(operation))
                    .toList();
        }

        List<Command> commands() {
            return List.copyOf(commands);
        }

        private void respond(SocketChannel connection) {
            try (connection;
                    var input = Channels.newInputStream(connection);
                    var output = new DataOutputStream(Channels.newOutputStream(connection))) {
                byte[] prefix = input.readNBytes(4);
                if (prefix.length == 0) {
                    return;
                }
                JsonNode envelope =
                        json.readTree(input.readNBytes(ByteBuffer.wrap(prefix).getInt()));
                Object response;
                if (envelope.path("operation").asString("").equals("HELLO")) {
                    response = hello();
                } else {
                    JsonNode request = json.readTree(Base64.getUrlDecoder()
                            .decode(envelope.path("payload").stringValue()));
                    requests.add(request);
                    response = handle(request);
                }
                byte[] bytes = json.writeValueAsBytes(response);
                output.writeInt(bytes.length);
                output.write(bytes);
                output.flush();
            } catch (Throwable exception) {
                if (!closed.get()) {
                    failure.compareAndSet(null, exception);
                }
            }
        }

        private Map<String, Object> hello() {
            var hello = new LinkedHashMap<String, Object>();
            hello.put("ok", true);
            hello.put("version", 1);
            for (String marker : List.of(
                    "codeActProtocol",
                    "artifactProtocol",
                    "moveProtocol",
                    "exportProtocol",
                    "diskCopyProtocol",
                    "gitBaselineProtocol",
                    "workspaceSyncProtocol",
                    "leaseSandboxProtocol")) {
                hello.put(marker, 1);
            }
            hello.put("workerBootId", boot);
            hello.put("maxFrameBytes", WorkerClient.MAX_FRAME);
            // Renewal stays outside every test's duration, so the heartbeat cannot race the request.
            hello.put("leaseSeconds", 90);
            hello.put("renewAfterSeconds", 30);
            return hello;
        }

        private Map<String, Object> handle(JsonNode request) throws InterruptedException {
            String operation = request.path("operation").stringValue();
            String lease = request.path("leaseId").stringValue();
            JsonNode data = request.path("data");
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", true);
            response.put("requestId", request.path("requestId").stringValue());
            response.put("leaseId", lease);
            response.put("state", "READY");
            switch (operation) {
                case "OPEN" -> open(lease, data, response);
                case "ATTACH" -> attach(lease, data, response);
                case "EXEC" -> exec(lease, data, response);
                case "DISCARD" -> discard(data, response);
                case "CLOSE" -> response.put("state", "CLOSED");
                case "RENEW", "BRIDGE_POLL" -> response.put("bridgeRequest", null);
                default -> throw new IllegalArgumentException("unexpected operation " + operation);
            }
            if (!response.containsKey("commit")) {
                response.put("commit", leaseCommits.get(lease));
            }
            return response;
        }

        private void open(String lease, JsonNode data, Map<String, Object> response) {
            String copy = data.path("copyId").stringValue();
            String commit = data.path("commit").stringValue();
            if (copies.putIfAbsent(copy, commit) != null) {
                refuse(response, "INITIALIZATION_FAILED");
                return;
            }
            leaseCommits.put(lease, commit);
            if (failNextOpen) {
                failNextOpen = false;
                refuse(response, "INITIALIZATION_FAILED");
                return;
            }
            response.put("gitCommit", commit);
        }

        private void attach(String lease, JsonNode data, Map<String, Object> response) {
            String commit = data.path("commit").stringValue();
            if (!commit.equals(copies.get(data.path("copyId").stringValue()))) {
                refuse(response, "COPY_UNAVAILABLE");
                return;
            }
            leaseCommits.put(lease, commit);
            response.put("gitCommit", attachedGitCommit == null ? commit : attachedGitCommit);
        }

        private void exec(String lease, JsonNode data, Map<String, Object> response) throws InterruptedException {
            String command = data.path("command").stringValue();
            String commit = leaseCommits.get(lease);
            boolean fresh = startedUnits.add(lease);
            commands.add(new Command(lease, commit, command, fresh));
            boolean inspection = command.equals(PublicCopyRefresh.inspection(commit));
            if (inspection) {
                onInspection.run();
            }
            if (inspection && holdInspection) {
                inspectionEntered.countDown();
                assertThat(inspectionRelease.await(8, TimeUnit.SECONDS)).isTrue();
            }
            if (inspection && inspectionCapacity) {
                refuse(response, "EXECUTION_CAPACITY");
                return;
            }
            boolean clean = inspection && !dirty;
            var result = new LinkedHashMap<String, Object>();
            result.put("commit", commit);
            result.put("exitCode", inspection && !clean ? 1 : 0);
            result.put("stdout", clean ? "POKETTO_PUBLIC_COPY_CLEAN\n" : inspection ? "" : "fixture result");
            result.put("stderr", "");
            result.put("stdoutTruncated", false);
            result.put("stderrTruncated", false);
            result.put("timedOut", false);
            result.put("freshSandbox", fresh);
            result.put("terminationReason", "normal");
            result.put("artifacts", Map.of());
            result.put("artifactErrors", Map.of());
            response.put("result", result);
        }

        private void discard(JsonNode data, Map<String, Object> response) throws InterruptedException {
            if (holdDiscard) {
                discardEntered.countDown();
                assertThat(discardRelease.await(8, TimeUnit.SECONDS)).isTrue();
            }
            String copy = data.path("copyId").stringValue();
            String commit = data.path("commit").stringValue();
            String state = copies.remove(copy, commit) ? "DISCARDED" : "ABSENT";
            assertThat(copies).doesNotContainKey(copy);
            discarded.add(state);
            response.put("copyId", copy);
            response.put("commit", commit);
            response.put("state", state);
        }

        private static void refuse(Map<String, Object> response, String code) {
            response.put("ok", false);
            response.put("code", code);
        }

        @Override
        public void close() throws Exception {
            closed.set(true);
            server.close();
            threads.shutdownNow();
            assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            Files.deleteIfExists(path);
            assertThat(failure.get()).isNull();
        }
    }
}
