package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthRevocation;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

/** Real Unix sockets and Ed25519 frames test the adapter; this peer does not claim sandbox isolation. */
class WorkerSocketTests {
    private static final String COMMIT = "a".repeat(40);
    private static final WorkspaceId WORKSPACE = WorkspaceId.random();

    @Test
    void signsExactFramesPinsEachClientAndReleasesExportsAfterOpening() throws Exception {
        var auth = mock(AuthService.class);
        var exports = exports();
        var principal = principal();
        try (var peer = new Peer();
                var executor = executor(auth, exports, peer)) {
            var first = executor.execute(
                    principal,
                    WORKSPACE,
                    "client-one",
                    Optional.empty(),
                    "git log -1",
                    Duration.ofSeconds(1),
                    new Cancellation());
            assertThat(first.commit()).isEqualTo(COMMIT);
            assertThat(first.stdout()).isEqualTo("fixture result");
            executor.execute(
                    principal,
                    WORKSPACE,
                    "client-one",
                    Optional.empty(),
                    "git status",
                    Duration.ofSeconds(1),
                    new Cancellation());
            executor.execute(
                    principal,
                    WORKSPACE,
                    "client-two",
                    Optional.empty(),
                    "git status",
                    Duration.ofSeconds(1),
                    new Cancellation());
            assertThat(peer.operations("OPEN")).hasSize(2);
            assertThat(peer.operations("EXEC")).hasSize(3);
            assertThat(peer.operations("OPEN").stream()
                            .map(node -> node.path("serverSessionHash").stringValue())
                            .distinct())
                    .hasSize(2);
            assertThat(peer.operations("EXEC").get(0).path("leaseId"))
                    .isEqualTo(peer.operations("EXEC").get(1).path("leaseId"));
            assertThatThrownBy(() -> executor.execute(
                            principal,
                            WORKSPACE,
                            "client-one",
                            Optional.of("b".repeat(40)),
                            "pwd",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pinned");
            verify(exports, times(2)).release(any());
            verify(auth, atLeast(3))
                    .authorize(principal, WORKSPACE, Capability.READ_PRIVATE, Capability.EXECUTE_REPOSITORY);
            assertThat(peer.failure.get()).isNull();
        }
    }

    @Test
    void renewsWhileOpenIsPendingAndCancellationWaitsPastClosingWithoutExecuting() throws Exception {
        var auth = mock(AuthService.class);
        var exports = exports();
        try (var peer = new Peer();
                var executor = executor(auth, exports, peer)) {
            peer.blockOpen = true;
            peer.closeNeedsPolling = true;
            var cancellation = new Cancellation();
            var run = CompletableFuture.runAsync(() -> executor.execute(
                    principal(),
                    WORKSPACE,
                    "opening",
                    Optional.empty(),
                    "sleep 5",
                    Duration.ofSeconds(5),
                    cancellation));
            assertThat(peer.openEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(peer.renewEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(run).isNotDone();
            cancellation.cancel();
            assertThat(peer.operations("CLOSE")).hasSizeGreaterThanOrEqualTo(2);
            assertThatThrownBy(() -> run.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(WorkerUnavailableException.class);
            assertThat(peer.operations("EXEC")).isEmpty();
            verify(exports).release(any());
            assertThat(peer.failure.get()).isNull();
        }
    }

    @Test
    void lostExecReplyIsNeverReplayedAndLeavesTheSessionUnusable() throws Exception {
        var principal = principal();
        try (var peer = new Peer();
                var executor = executor(mock(AuthService.class), exports(), peer)) {
            peer.dropExec = true;
            assertThatThrownBy(() -> executor.execute(
                            principal,
                            WORKSPACE,
                            "lost",
                            Optional.empty(),
                            "touch local.txt",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThat(peer.operations("EXEC")).hasSize(1);
            assertThat(peer.operations("CLOSE")).isNotEmpty();
            assertThatThrownBy(() -> executor.execute(
                            principal,
                            WORKSPACE,
                            "lost",
                            Optional.empty(),
                            "touch local.txt",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThat(peer.operations("EXEC")).hasSize(1);
        }
    }

    @Test
    void revocationSendsWorkspaceTombstonesAndStopsActiveLeases() throws Exception {
        var principal = principal();
        try (var peer = new Peer();
                var executor = executor(mock(AuthService.class), exports(), peer)) {
            executor.execute(
                    principal,
                    WORKSPACE,
                    "revoked",
                    Optional.empty(),
                    "pwd",
                    Duration.ofSeconds(1),
                    new Cancellation());
            executor.revoked(new AuthRevocation(WORKSPACE, Set.of(), Set.of(principal.subjectId())));
            JsonNode revoked = peer.operations("REVOKE").getFirst();
            assertThat(revoked.path("workspaceId").stringValue())
                    .isEqualTo(WORKSPACE.value().toString());
            assertThat(revoked.path("data").path("keyIds").get(0).stringValue())
                    .isEqualTo(principal.subjectId().toString());
            assertThat(peer.operations("CLOSE")).isNotEmpty();
            assertThatThrownBy(() -> executor.execute(
                            principal,
                            WORKSPACE,
                            "revoked",
                            Optional.empty(),
                            "pwd",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(WorkerUnavailableException.class);
            executor.revoked(new AuthRevocation(WORKSPACE, Set.of(UUID.randomUUID()), Set.of()));
            assertThat(peer.operations("REVOKE")).hasSize(2);
        }
    }

    @Test
    void unauthorizedOrAlreadyCancelledCallsNeverContactTheWorker() throws Exception {
        var auth = mock(AuthService.class);
        var principal = principal();
        try (var peer = new Peer();
                var executor = executor(auth, exports(), peer)) {
            var cancelled = new Cancellation();
            cancelled.cancel();
            assertThatThrownBy(() -> executor.execute(
                            principal,
                            WORKSPACE,
                            "cancelled",
                            Optional.empty(),
                            "pwd",
                            Duration.ofSeconds(1),
                            cancelled))
                    .isInstanceOf(WorkerUnavailableException.class);
            doThrow(new SecurityException("denied"))
                    .when(auth)
                    .authorize(principal, WORKSPACE, Capability.READ_PRIVATE, Capability.EXECUTE_REPOSITORY);
            assertThatThrownBy(() -> executor.execute(
                            principal,
                            WORKSPACE,
                            "denied",
                            Optional.empty(),
                            "pwd",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(SecurityException.class);
            assertThat(peer.requests).isEmpty();
        }
    }

    private static IsolatedRepositoryExecutor executor(AuthService auth, RepositorySnapshotExports exports, Peer peer) {
        return new IsolatedRepositoryExecutor(
                auth, exports, peer.client(), 8, Duration.ofSeconds(8), Duration.ofSeconds(3));
    }

    @Test
    void cancellingDuringExportCannotRaceIntoAnOpenRequest() throws Exception {
        var exports = exports();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(exports.create(any(), any(), any())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return new RepositorySnapshotExports.Export(UUID.randomUUID(), COMMIT, "b".repeat(64), 128);
        });
        try (var peer = new Peer();
                var executor = executor(mock(AuthService.class), exports, peer)) {
            var cancellation = new Cancellation();
            var run = CompletableFuture.runAsync(() -> executor.execute(
                    principal(), WORKSPACE, "exporting", Optional.empty(), "pwd", Duration.ofSeconds(1), cancellation));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                cancellation.cancel();
            } finally {
                release.countDown();
            }
            assertThatThrownBy(() -> run.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(WorkerUnavailableException.class);
            assertThat(peer.operations("OPEN")).isEmpty();
            assertThat(peer.operations("EXEC")).isEmpty();
            verify(exports).release(any());
        }
    }

    @Test
    void workerFramesRequestIdentityAndTimeoutsAreBounded() throws Exception {
        try (var peer = new Peer()) {
            peer.oversizedHello = true;
            assertThatThrownBy(() -> peer.client().hello()).isInstanceOf(WorkerUnavailableException.class);
            peer.oversizedHello = false;
            WorkerClient client = peer.client();
            var hello = client.hello();
            var identity = new WorkerClient.Identity(
                    UUID.randomUUID(), UUID.randomUUID(), WORKSPACE.value(), "0".repeat(64), UUID.randomUUID());
            peer.wrongRequestId = true;
            assertThatThrownBy(() -> client.request(hello, identity, "EXEC", Map.of(), Duration.ofSeconds(1)))
                    .isInstanceOf(WorkerUnavailableException.class);
            peer.wrongRequestId = false;
            peer.stallExec = true;
            long start = System.nanoTime();
            assertThatThrownBy(() -> client.request(hello, identity, "EXEC", Map.of(), Duration.ofMillis(100)))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            assertThat(peer.operations("EXEC")).hasSize(2);
        }
    }

    private static AuthPrincipal principal() {
        var principal = mock(AuthPrincipal.class);
        when(principal.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(principal.subjectId()).thenReturn(UUID.randomUUID());
        when(principal.accountId()).thenReturn(UUID.randomUUID());
        return principal;
    }

    @Test
    void workerLeaseAndLifecycleReasonsMapToExplicitPortResults() throws Exception {
        try (var peer = new Peer();
                var executor = executor(mock(AuthService.class), exports(), peer)) {
            for (String reason : List.of("session_closed", "client_shutdown", "lease_expired")) {
                peer.terminationReason = reason;
                var result = executor.execute(
                        principal(),
                        WORKSPACE,
                        reason,
                        Optional.empty(),
                        "pwd",
                        Duration.ofSeconds(1),
                        new Cancellation());
                assertThat(result.terminationReason())
                        .isEqualTo(
                                reason.equals("lease_expired")
                                        ? io.github.core607.poketto.mcp.RepositoryExecutor.TerminationReason
                                                .SANDBOX_FAILURE
                                        : io.github.core607.poketto.mcp.RepositoryExecutor.TerminationReason.CANCELLED);
            }
            assertThat(peer.operations("CLOSE")).hasSize(3);
        }
    }

    @Test
    void confirmedCloseReleasesAdmissionButOldSessionCannotReopenWithoutDelete() throws Exception {
        var principal = principal();
        try (var peer = new Peer();
                var executor = new IsolatedRepositoryExecutor(
                        mock(AuthService.class),
                        exports(),
                        peer.client(),
                        1,
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(1))) {
            peer.terminationReason = "cancelled";
            executor.execute(
                    principal,
                    WORKSPACE,
                    "closed-A",
                    Optional.empty(),
                    "pwd",
                    Duration.ofSeconds(1),
                    new Cancellation());
            peer.terminationReason = "normal";
            assertThat(executor.execute(
                                    principal,
                                    WORKSPACE,
                                    "fresh-B",
                                    Optional.empty(),
                                    "pwd",
                                    Duration.ofSeconds(1),
                                    new Cancellation())
                            .exitCode())
                    .isZero();
            assertThatThrownBy(() -> executor.execute(
                            principal,
                            WORKSPACE,
                            "closed-A",
                            Optional.empty(),
                            "pwd",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThat(peer.operations("OPEN")).hasSize(2);
        }
    }

    @Test
    void failureBeforeOpenReleasesAdmissionForAnotherClient() throws Exception {
        var exports = exports();
        when(exports.create(any(), any(), any()))
                .thenThrow(new IllegalStateException("synthetic export failure"))
                .thenReturn(new RepositorySnapshotExports.Export(UUID.randomUUID(), COMMIT, "b".repeat(64), 128));
        try (var peer = new Peer();
                var executor = new IsolatedRepositoryExecutor(
                        mock(AuthService.class),
                        exports,
                        peer.client(),
                        1,
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> executor.execute(
                            principal(),
                            WORKSPACE,
                            "failed-A",
                            Optional.empty(),
                            "pwd",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(executor.execute(
                                    principal(),
                                    WORKSPACE,
                                    "fresh-B",
                                    Optional.empty(),
                                    "pwd",
                                    Duration.ofSeconds(1),
                                    new Cancellation())
                            .exitCode())
                    .isZero();
            assertThat(peer.operations("OPEN")).hasSize(1);
        }
    }

    @Test
    void unconfirmedClosingAndDeleteRetainWorkerAdmission() throws Exception {
        var principal = principal();
        try (var peer = new Peer();
                var executor = new IsolatedRepositoryExecutor(
                        mock(AuthService.class),
                        exports(),
                        peer.client(),
                        1,
                        Duration.ofSeconds(3),
                        Duration.ofMillis(100))) {
            executor.execute(
                    principal,
                    WORKSPACE,
                    "closing-A",
                    Optional.empty(),
                    "pwd",
                    Duration.ofSeconds(1),
                    new Cancellation());
            peer.closeForever = true;
            assertThatThrownBy(() -> executor.closed(new io.github.core607.poketto.mcp.McpSessionClosed(
                            WORKSPACE,
                            principal.subjectId(),
                            "closing-A",
                            io.github.core607.poketto.mcp.McpSessionClosed.Reason.CLIENT_DELETE)))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThatThrownBy(() -> executor.execute(
                            principal,
                            WORKSPACE,
                            "fresh-B",
                            Optional.empty(),
                            "pwd",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThat(peer.operations("OPEN")).hasSize(1);
        }
    }

    @Test
    void missingCloseReplyDoesNotReleaseAdmissionEvenAfterClientDelete() throws Exception {
        var principal = principal();
        try (var peer = new Peer();
                var executor = new IsolatedRepositoryExecutor(
                        mock(AuthService.class),
                        exports(),
                        peer.client(),
                        1,
                        Duration.ofSeconds(3),
                        Duration.ofMillis(100))) {
            peer.terminationReason = "cancelled";
            peer.dropClose = true;
            assertThatThrownBy(() -> executor.execute(
                            principal,
                            WORKSPACE,
                            "unknown-A",
                            Optional.empty(),
                            "pwd",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThatThrownBy(() -> executor.closed(new io.github.core607.poketto.mcp.McpSessionClosed(
                            WORKSPACE,
                            principal.subjectId(),
                            "unknown-A",
                            io.github.core607.poketto.mcp.McpSessionClosed.Reason.CLIENT_DELETE)))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThatThrownBy(() -> executor.execute(
                            principal,
                            WORKSPACE,
                            "fresh-B",
                            Optional.empty(),
                            "pwd",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThat(peer.operations("OPEN")).hasSize(1);
        }
    }

    @Test
    void aNewWorkerBootRecoversSaturatedAdmissionWithoutReopeningTheOldClient() throws Exception {
        var principal = principal();
        try (var peer = new Peer();
                var executor = new IsolatedRepositoryExecutor(
                        mock(AuthService.class),
                        exports(),
                        peer.client(),
                        1,
                        Duration.ofSeconds(3),
                        Duration.ofMillis(100))) {
            executor.execute(
                    principal, WORKSPACE, "old-A", Optional.empty(), "pwd", Duration.ofSeconds(1), new Cancellation());
            peer.boot = UUID.randomUUID();
            assertThatThrownBy(() -> executor.execute(
                            principal,
                            WORKSPACE,
                            "old-A",
                            Optional.empty(),
                            "pwd",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(WorkerUnavailableException.class);
            peer.dropHello = true;
            assertThatThrownBy(() -> executor.execute(
                            principal,
                            WORKSPACE,
                            "new-B",
                            Optional.empty(),
                            "pwd",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThat(peer.operations("OPEN")).hasSize(1);
            peer.dropHello = false;
            assertThat(executor.execute(
                                    principal,
                                    WORKSPACE,
                                    "new-B",
                                    Optional.empty(),
                                    "pwd",
                                    Duration.ofSeconds(1),
                                    new Cancellation())
                            .exitCode())
                    .isZero();
            assertThatThrownBy(() -> executor.execute(
                            principal,
                            WORKSPACE,
                            "old-A",
                            Optional.empty(),
                            "pwd",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThat(peer.operations("OPEN")).hasSize(2);
        }
    }

    @Test
    void aNewWorkerBootRetiresAnOccupiedLeaseBeforeItsOldClientSendsAnotherRequest() throws Exception {
        var principal = principal();
        try (var peer = new Peer();
                var executor = new IsolatedRepositoryExecutor(
                        mock(AuthService.class),
                        exports(),
                        peer.client(),
                        1,
                        Duration.ofSeconds(3),
                        Duration.ofMillis(100))) {
            executor.execute(
                    principal, WORKSPACE, "old-A", Optional.empty(), "pwd", Duration.ofSeconds(1), new Cancellation());
            peer.boot = UUID.randomUUID();
            assertThat(executor.execute(
                                    principal,
                                    WORKSPACE,
                                    "new-B",
                                    Optional.empty(),
                                    "pwd",
                                    Duration.ofSeconds(1),
                                    new Cancellation())
                            .exitCode())
                    .isZero();
            assertThatThrownBy(() -> executor.execute(
                            principal,
                            WORKSPACE,
                            "old-A",
                            Optional.empty(),
                            "pwd",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThat(peer.operations("OPEN")).hasSize(2);
        }
    }

    private static RepositorySnapshotExports exports() {
        var exports = mock(RepositorySnapshotExports.class);
        when(exports.create(any(), any(), any()))
                .thenAnswer(
                        call -> new RepositorySnapshotExports.Export(UUID.randomUUID(), COMMIT, "b".repeat(64), 128));
        return exports;
    }

    @Test
    void replacementDecodedOutputRetainsTheFullBoundedWorkerResult() throws Exception {
        try (var peer = new Peer();
                var executor = executor(mock(AuthService.class), exports(), peer)) {
            peer.stdout = "\uFFFD".repeat(65536);
            var result = executor.execute(
                    principal(),
                    WORKSPACE,
                    "replacement",
                    Optional.empty(),
                    "pwd",
                    Duration.ofSeconds(1),
                    new Cancellation());
            assertThat(result.stdout()).isEqualTo(peer.stdout);
            peer.stdout += "\uFFFD";
            assertThatThrownBy(() -> executor.execute(
                            principal(),
                            WORKSPACE,
                            "too-large",
                            Optional.empty(),
                            "pwd",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(WorkerUnavailableException.class);
        }
    }

    private static final class Cancellation implements ExecutionCancellation {
        private boolean cancelled;
        private final List<Runnable> callbacks = new ArrayList<>();

        @Override
        public synchronized boolean isCancelled() {
            return cancelled;
        }

        @Override
        public Registration onCancel(Runnable callback) {
            synchronized (this) {
                if (!cancelled) {
                    callbacks.add(callback);
                    return () -> {
                        synchronized (this) {
                            callbacks.remove(callback);
                        }
                    };
                }
            }
            callback.run();
            return () -> {};
        }

        void cancel() {
            List<Runnable> pending;
            synchronized (this) {
                cancelled = true;
                pending = List.copyOf(callbacks);
                callbacks.clear();
            }
            pending.forEach(Runnable::run);
        }
    }

    private static final class Peer implements AutoCloseable {
        private final ObjectMapper json = new ObjectMapper();
        private final KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        private volatile UUID boot = UUID.randomUUID();
        private final Path path;
        private final ServerSocketChannel server;
        private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Map<String, String> states = new ConcurrentHashMap<>();
        private final CountDownLatch openEntered = new CountDownLatch(1);
        private final CountDownLatch openRelease = new CountDownLatch(1);
        private final CountDownLatch renewEntered = new CountDownLatch(1);
        private volatile boolean blockOpen;
        private volatile boolean closeNeedsPolling;
        private volatile boolean closeForever;
        private volatile boolean dropClose;
        private volatile boolean dropHello;
        private volatile boolean dropExec;
        private volatile boolean oversizedHello;
        private volatile boolean wrongRequestId;
        private volatile boolean stallExec;
        private volatile String terminationReason = "normal";
        private volatile String stdout = "fixture result";

        Peer() throws Exception {
            Path directory = Path.of(".gradle", "uds").toAbsolutePath();
            Files.createDirectories(directory);
            path = directory.resolve("w-" + UUID.randomUUID().toString().substring(0, 8));
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(path));
            threads.submit(() -> {
                while (!closed.get()) {
                    try {
                        SocketChannel connection = server.accept();
                        threads.submit(() -> respond(connection));
                    } catch (Exception exception) {
                        if (!closed.get()) failure.compareAndSet(null, exception);
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

        private void respond(SocketChannel connection) {
            try (connection;
                    var input = new DataInputStream(Channels.newInputStream(connection));
                    var output = new DataOutputStream(Channels.newOutputStream(connection))) {
                int length = input.readInt();
                assertThat(length).isBetween(1, WorkerClient.MAX_FRAME);
                JsonNode envelope = json.readTree(input.readNBytes(length));
                Object response;
                if (envelope.path("operation").asString("").equals("HELLO")) {
                    if (dropHello) return;
                    if (oversizedHello) {
                        output.writeInt(WorkerClient.MAX_FRAME + 1);
                        output.flush();
                        return;
                    }
                    response = Map.of(
                            "ok",
                            true,
                            "version",
                            1,
                            "workerBootId",
                            boot,
                            "maxFrameBytes",
                            WorkerClient.MAX_FRAME,
                            "leaseSeconds",
                            10,
                            "renewAfterSeconds",
                            1);
                } else {
                    byte[] payload = Base64.getUrlDecoder()
                            .decode(envelope.path("payload").stringValue());
                    Signature verifier = Signature.getInstance("Ed25519");
                    verifier.initVerify(key.getPublic());
                    verifier.update(payload);
                    assertThat(verifier.verify(Base64.getUrlDecoder()
                                    .decode(envelope.path("signature").stringValue())))
                            .isTrue();
                    JsonNode request = json.readTree(payload);
                    requests.add(request);
                    assertThat(request.path("serverSessionHash").stringValue()).matches("[0-9a-f]{64}");
                    assertThat(request.path("expiresAt").longValue()
                                    - request.path("issuedAt").longValue())
                            .isEqualTo(10);
                    response = request.path("workerBootId").asString("").equals(boot.toString())
                            ? handle(request)
                            : Map.of(
                                    "ok",
                                    false,
                                    "code",
                                    "WORKER_RESTARTED",
                                    "requestId",
                                    request.path("requestId").stringValue());
                    if (response == null) return;
                }
                byte[] bytes = json.writeValueAsBytes(response);
                output.writeInt(bytes.length);
                output.write(bytes);
                output.flush();
            } catch (Throwable exception) {
                if (!closed.get() && !(stallExec && exception instanceof java.io.IOException))
                    failure.compareAndSet(null, exception);
            }
        }

        private Object handle(JsonNode request) throws Exception {
            String operation = request.path("operation").stringValue();
            String lease = request.path("leaseId").stringValue();
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("ok", true);
            response.put(
                    "requestId",
                    wrongRequestId
                            ? UUID.randomUUID().toString()
                            : request.path("requestId").stringValue());
            response.put("leaseId", lease);
            response.put("commit", COMMIT);
            response.put("state", "READY");
            switch (operation) {
                case "OPEN" -> {
                    states.put(lease, "INITIALIZING");
                    openEntered.countDown();
                    if (blockOpen)
                        assertThat(openRelease.await(8, TimeUnit.SECONDS)).isTrue();
                    states.putIfAbsent(lease, "READY");
                }
                case "RENEW" -> {
                    renewEntered.countDown();
                    response.put("state", states.getOrDefault(lease, "INITIALIZING"));
                }
                case "EXEC" -> {
                    if (dropExec) return null;
                    if (stallExec) Thread.sleep(500);
                    response.put(
                            "result",
                            Map.of(
                                    "commit",
                                    COMMIT,
                                    "exitCode",
                                    0,
                                    "stdout",
                                    stdout,
                                    "stderr",
                                    "",
                                    "stdoutTruncated",
                                    false,
                                    "stderrTruncated",
                                    false,
                                    "timedOut",
                                    false,
                                    "terminationReason",
                                    terminationReason));
                }
                case "CLOSE" -> {
                    if (dropClose) return null;
                    String state = closeForever
                                    || closeNeedsPolling
                                            && !states.getOrDefault(lease, "").equals("CLOSING")
                            ? "CLOSING"
                            : "CLOSED";
                    states.put(lease, state);
                    response.put("state", state);
                    if (state.equals("CLOSED")) openRelease.countDown();
                }
                case "REVOKE" -> {
                    response.put("state", "CLOSED");
                    response.put("closedCount", 0);
                }
                default -> throw new IllegalArgumentException();
            }
            return response;
        }

        @Override
        public void close() throws Exception {
            closed.set(true);
            openRelease.countDown();
            server.close();
            threads.shutdownNow();
            assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            Files.deleteIfExists(path);
            assertThat(failure.get()).isNull();
        }
    }
}
