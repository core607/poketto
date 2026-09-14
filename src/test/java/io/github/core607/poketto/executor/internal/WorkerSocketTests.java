package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.assets.MediaFileService;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthRevocation;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PortableContentExports;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.ExecutionUnconfirmedException;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.mcp.SessionReplacedException;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.DataOutputStream;
import java.io.IOException;
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
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
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
    private final RememberingExecutorClient client = new RememberingExecutorClient();
    private static final String COMMIT = "a".repeat(40);
    private static final WorkspaceId WORKSPACE = WorkspaceId.random();

    @Test
    void failedGitInstallationLeavesInspectionAvailableWithoutRetryingOnEachCommand() throws Exception {
        var actor = principal();
        var saves = mock(SelectedFileSaves.class);
        var exports = exports();
        String target = "d".repeat(40);
        when(exports.update(any(), any(), any(), any()))
                .thenReturn(new RepositorySnapshotExports.Export(UUID.randomUUID(), target, "e".repeat(64), 128));
        doAnswer(call -> {
                    SelectedFileSaves.State state = call.getArgument(2);
                    state.baseCommit = target;
                    return BridgeReplies.succeeded(new BridgeReplies.Recovery(true));
                })
                .when(saves)
                .recover(any(), any(), any());
        try (var peer = new Peer();
                var executor = new IsolatedRepositoryExecutor(
                        peer.accounts.store(),
                        mock(PortableContentExports.class),
                        mock(MediaFileService.class),
                        saves,
                        fullAuth(),
                        exports,
                        peer.client(),
                        8,
                        Duration.ofSeconds(8),
                        Duration.ofSeconds(3))) {
            peer.baselineUnavailable = true;
            peer.bridgeCommand =
                    Map.of("requestId", UUID.randomUUID().toString(), "operation", "recover", "arguments", Map.of());
            var client = new RememberingExecutorClient();
            var first = client.execute(
                    executor,
                    actor,
                    WORKSPACE,
                    "one",
                    Optional.empty(),
                    "poketto recover",
                    Duration.ofSeconds(3),
                    new Cancellation());
            assertThat(peer.operations("BRIDGE_COMPLETE")
                            .getFirst()
                            .path("data")
                            .path("response")
                            .path("code")
                            .asString())
                    .isEqualTo("LOCAL_BASELINE_PENDING");
            peer.bridgeCommand = null;
            var inspected = client.execute(
                    executor,
                    actor,
                    WORKSPACE,
                    "one",
                    Optional.empty(),
                    "cat draft.md",
                    Duration.ofSeconds(3),
                    new Cancellation());
            assertThat(inspected.copyId()).isEqualTo(first.copyId());
            assertThat(inspected.retention().lastInterruptedCommand()).isNull();
            assertThat(peer.operations("BASELINE")).hasSize(1);
            assertThat(peer.operations("CLOSE")).isEmpty();
        }
    }

    @Test
    void replayCapacityRefusesWithoutMarkingAnInterruptionAndNextLeaseKeepsTheCopy() throws Exception {
        var actor = principal();
        var client = new RememberingExecutorClient();
        try (var peer = new Peer();
                var executor = executor(fullAuth(), exports(), peer)) {
            var first = client.execute(
                    executor,
                    actor,
                    WORKSPACE,
                    "one",
                    Optional.empty(),
                    "pwd",
                    Duration.ofSeconds(1),
                    new Cancellation());
            peer.executionCapacity = true;
            assertThatThrownBy(() -> client.execute(
                            executor,
                            actor,
                            WORKSPACE,
                            "one",
                            Optional.empty(),
                            "pwd",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOfSatisfying(
                            ExecutionAdmissionException.class,
                            refused -> assertThat(refused.reason())
                                    .isEqualTo(ExecutionAdmissionException.Reason.CAPACITY));
            peer.executionCapacity = false;
            var continued = client.execute(
                    executor,
                    actor,
                    WORKSPACE,
                    "one",
                    Optional.empty(),
                    "pwd",
                    Duration.ofSeconds(1),
                    new Cancellation());
            assertThat(continued.copyId()).isEqualTo(first.copyId());
            assertThat(continued.retention().lastInterruptedCommand()).isNull();
            assertThat(peer.operations("ATTACH")).hasSize(1);
        }
    }

    @Test
    void unavailableRemoteDoesNotHideLocalStatus() throws Exception {
        var saves = mock(SelectedFileSaves.class);
        when(saves.currentCommit(any(), any())).thenThrow(new ContentRepositoryException("Remote fetch unavailable"));
        try (var peer = new Peer();
                var executor = new IsolatedRepositoryExecutor(
                        peer.accounts.store(),
                        mock(PortableContentExports.class),
                        mock(MediaFileService.class),
                        saves,
                        fullAuth(),
                        exports(),
                        peer.client(),
                        8,
                        Duration.ofSeconds(8),
                        Duration.ofSeconds(3))) {
            peer.bridgeCommand =
                    Map.of("requestId", UUID.randomUUID().toString(), "operation", "status", "arguments", Map.of());
            var result = command(executor, principal(), "new");
            assertThat(result.exitCode()).isZero();
            JsonNode reply =
                    peer.operations("BRIDGE_COMPLETE").getFirst().path("data").path("response");
            assertThat(reply.path("ok").asBoolean()).isTrue();
            reply = reply.path("result");
            assertThat(reply.path("baseCommit").asText()).isEqualTo(COMMIT);
            assertThat(reply.path("remote").path("state").asText()).isEqualTo("UNAVAILABLE");
            assertThat(reply.path("remote").path("commit").isNull()).isTrue();
            assertThat(reply.has("lastSave")).isTrue();
            assertThat(reply.path("copyId").asText()).isEqualTo(result.copyId());
        }
    }

    @Test
    void transportExpiryKeepsTheAccountCopyAndExactIdentityGuard() throws Exception {
        var actor = principal();
        var exports = exports();
        var meters = new SimpleMeterRegistry();
        try (var peer = new Peer();
                var executor = executor(fullAuth(), exports, peer)) {
            executor.bindMetrics(meters);
            var first = client.execute(
                    executor,
                    actor,
                    WORKSPACE,
                    "before-idle",
                    Optional.empty(),
                    "pwd",
                    Duration.ofSeconds(2),
                    new Cancellation());
            var next = executor.execute(
                    actor,
                    WORKSPACE,
                    "after-idle",
                    new RepositoryExecutor.CopyRequest(first.copyId()),
                    Optional.empty(),
                    "pwd",
                    Duration.ofSeconds(2),
                    new Cancellation());
            assertThat(next.copyId()).isEqualTo(first.copyId());
            assertThat(next.commit()).isEqualTo(first.commit());
            assertThat(next.retention().expiresAt())
                    .isGreaterThanOrEqualTo(first.retention().expiresAt());
            assertThatThrownBy(() -> executor.execute(
                            actor,
                            WORKSPACE,
                            "after-idle",
                            new RepositoryExecutor.CopyRequest(UUID.randomUUID().toString()),
                            Optional.empty(),
                            "must-not-run",
                            Duration.ofSeconds(2),
                            new Cancellation()))
                    .isInstanceOf(SessionReplacedException.class);
            assertThat(peer.operations("EXEC")).hasSize(2);
            assertThat(peer.operations("OPEN")).hasSize(1);
            assertThat(peer.operations("CLOSE")).isEmpty();
            verify(exports, times(1)).create(any(), any(), any());
            assertThat(meters.get("poketto.executor.sessions.created")
                            .functionCounter()
                            .count())
                    .isEqualTo(1);
            assertThat(meters.get("poketto.executor.operations.active").gauge().value())
                    .isZero();
            assertThat(meters.get("poketto.executor.admission.rejected")
                            .tag("reason", "copy_mismatch")
                            .functionCounter()
                            .count())
                    .isEqualTo(1);
            assertThat(meters.getMeters())
                    .allSatisfy(meter -> assertThat(meter.getId().getTags())
                            .allSatisfy(tag -> assertThat(tag.getKey()).isEqualTo("reason")));
        } finally {
            meters.close();
        }
    }

    @Test
    void publisherRecoveryDelegatesWithoutRequiringPrivateWrite() throws Exception {
        var actor = principal();
        var auth = mock(AuthService.class);
        var grants = Set.of(Capability.READ_PRIVATE, Capability.PUBLISH, Capability.EXECUTE_REPOSITORY);
        doAnswer(call -> {
                    Capability[] required = (Capability[]) call.getRawArguments()[2];
                    if (!grants.containsAll(List.of(required))) {
                        throw new AuthException(AuthException.Code.DENIED);
                    }
                    return new WorkspaceAccess(WORKSPACE, actor, MembershipRole.MEMBER, grants);
                })
                .when(auth)
                .authorize(eq(actor), eq(WORKSPACE), any(Capability[].class));
        var saves = mock(SelectedFileSaves.class);
        doReturn(BridgeReplies.succeeded(new BridgeReplies.Recovery(false)))
                .when(saves)
                .recover(eq(actor), eq(WORKSPACE), any());
        try (var peer = new Peer();
                var executor = new IsolatedRepositoryExecutor(
                        peer.accounts.store(),
                        mock(PortableContentExports.class),
                        mock(MediaFileService.class),
                        saves,
                        auth,
                        exports(),
                        peer.client(),
                        8,
                        Duration.ofSeconds(8),
                        Duration.ofSeconds(3))) {
            peer.bridgeCommand =
                    Map.of("requestId", UUID.randomUUID().toString(), "operation", "recover", "arguments", Map.of());
            assertThat(executor.execute(
                                    actor,
                                    WORKSPACE,
                                    "publisher-recover",
                                    new RepositoryExecutor.CopyRequest("new"),
                                    Optional.empty(),
                                    "poketto recover",
                                    Duration.ofSeconds(3),
                                    new Cancellation())
                            .exitCode())
                    .isZero();
            assertThat(peer.operations("BRIDGE_COMPLETE")).hasSize(1);
            assertThat(peer.operations("BRIDGE_COMPLETE")
                            .getFirst()
                            .path("data")
                            .path("response")
                            .path("ok")
                            .asBoolean())
                    .isTrue();
            verify(saves).recover(eq(actor), eq(WORKSPACE), any());
        }
    }

    @Test
    void parallelChatsShareOneCopyWithoutExposingItToForeignIdentities() throws Exception {
        var principal = principal();
        var auth = fullAuth();
        try (var peer = new Peer();
                var executor = executor(auth, exports(), peer)) {
            var left = executor.execute(
                    principal,
                    WORKSPACE,
                    "left",
                    new RepositoryExecutor.CopyRequest("new"),
                    Optional.empty(),
                    "pwd",
                    Duration.ofSeconds(2),
                    new Cancellation());
            var right = executor.execute(
                    principal,
                    WORKSPACE,
                    "right",
                    new RepositoryExecutor.CopyRequest("new"),
                    Optional.empty(),
                    "pwd",
                    Duration.ofSeconds(2),
                    new Cancellation());
            assertThat(left.copyId()).isEqualTo(right.copyId());
            assertThat(executor.execute(
                                    principal,
                                    WORKSPACE,
                                    "right",
                                    new RepositoryExecutor.CopyRequest(left.copyId()),
                                    Optional.empty(),
                                    "pwd",
                                    Duration.ofSeconds(2),
                                    new Cancellation())
                            .copyId())
                    .isEqualTo(left.copyId());
            assertThatThrownBy(() -> executor.execute(
                            principal(),
                            WORKSPACE,
                            "right",
                            new RepositoryExecutor.CopyRequest(right.copyId()),
                            Optional.empty(),
                            "write-sentinel",
                            Duration.ofSeconds(2),
                            new Cancellation()))
                    .isInstanceOfSatisfying(
                            SessionReplacedException.class,
                            failure -> assertThat(failure.currentCopyId()).isEmpty());
            assertThatThrownBy(() -> executor.execute(
                            principal,
                            WorkspaceId.random(),
                            "right",
                            new RepositoryExecutor.CopyRequest(right.copyId()),
                            Optional.empty(),
                            "write-sentinel",
                            Duration.ofSeconds(2),
                            new Cancellation()))
                    .isInstanceOfSatisfying(
                            SessionReplacedException.class,
                            failure -> assertThat(failure.currentCopyId()).isEmpty());
            when(auth.authorize(principal, WORKSPACE, Capability.READ_PRIVATE, Capability.EXECUTE_REPOSITORY))
                    .thenThrow(new AuthException(AuthException.Code.DENIED));
            assertThatThrownBy(() -> executor.execute(
                            principal,
                            WORKSPACE,
                            "right",
                            new RepositoryExecutor.CopyRequest(left.copyId()),
                            Optional.empty(),
                            "write-sentinel",
                            Duration.ofSeconds(2),
                            new Cancellation()))
                    .isInstanceOf(AuthException.class);
            assertThat(peer.operations("EXEC")).hasSize(3);
            assertThat(peer.operations("OPEN")).hasSize(1);
        }
    }

    @Test
    void overlappingInitialCallsWaitForTheSameAcknowledgedCopy() throws Exception {
        var actor = principal();
        try (var peer = new Peer();
                var executor = executor(fullAuth(), exports(), peer)) {
            peer.stallExec = true;
            var first = CompletableFuture.supplyAsync(() -> client.execute(
                    executor,
                    actor,
                    WORKSPACE,
                    "one",
                    Optional.empty(),
                    "pwd",
                    Duration.ofSeconds(2),
                    new Cancellation()));
            assertThat(peer.execEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = client.execute(
                    executor,
                    actor,
                    WORKSPACE,
                    "two",
                    Optional.empty(),
                    "pwd",
                    Duration.ofSeconds(2),
                    new Cancellation());
            assertThat(second.copyId()).isEqualTo(first.get(5, TimeUnit.SECONDS).copyId());
            assertThat(peer.operations("EXEC")).hasSize(2);
            assertThat(peer.operations("OPEN")).hasSize(1);
        }
    }

    @Test
    void incompatibleWorkerIsRejectedBeforeOpeningOrExportingContent() throws Exception {
        var exports = exports();
        try (var peer = new Peer();
                var executor = executor(fullAuth(), exports, peer)) {
            peer.codeActProtocol = 0;
            assertThatThrownBy(() -> client.execute(
                            executor,
                            principal(),
                            WORKSPACE,
                            "unsupported",
                            Optional.empty(),
                            "pwd",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(ExecutionAdmissionException.class);
            assertThat(peer.operations("OPEN")).isEmpty();
            verify(exports, never()).create(any(), any(), any());
            verify(exports, never()).createPublic(any(), any());
            peer.codeActProtocol = 2;
            assertThatThrownBy(() -> peer.client().hello()).isInstanceOf(WorkerUnavailableException.class);
            peer.codeActProtocol = 1;
            peer.artifactProtocol = 0;
            assertThatThrownBy(() -> peer.client().hello()).isInstanceOf(WorkerUnavailableException.class);
            peer.artifactProtocol = 1;
            peer.moveProtocol = 0;
            assertThatThrownBy(() -> peer.client().hello()).isInstanceOf(WorkerUnavailableException.class);
            peer.moveProtocol = 1;
            peer.exportProtocol = 0;
            assertThatThrownBy(() -> peer.client().hello()).isInstanceOf(WorkerUnavailableException.class);
            peer.exportProtocol = 1;
            assertThat(peer.client().hello().workerBootId()).isEqualTo(peer.boot);
        }
    }

    @Test
    void closingATransportKeepsAccountOwnedExportsAndLease() throws Exception {
        var actor = principal();
        var packages = mock(PortableContentExports.class);
        try (var peer = new Peer();
                var executor = new IsolatedRepositoryExecutor(
                        peer.accounts.store(),
                        packages,
                        mock(MediaFileService.class),
                        mock(SelectedFileSaves.class),
                        fullAuth(),
                        exports(),
                        peer.client(),
                        8,
                        Duration.ofSeconds(8),
                        Duration.ofSeconds(3))) {
            var first = client.execute(
                    executor,
                    actor,
                    WORKSPACE,
                    "one",
                    Optional.empty(),
                    "pwd",
                    Duration.ofSeconds(2),
                    new Cancellation());
            assertThat(peer.operations("CLOSE")).isEmpty();
            verifyNoInteractions(packages);
            assertThat(client.execute(
                                    executor,
                                    actor,
                                    WORKSPACE,
                                    "two",
                                    Optional.empty(),
                                    "pwd",
                                    Duration.ofSeconds(2),
                                    new Cancellation())
                            .copyId())
                    .isEqualTo(first.copyId());
        }
    }

    @Test
    void publicReadersOpenOnlyProjectionAndCannotSelectSourceHistoryOrSilentlyUpgrade() throws Exception {
        AuthService auth = fullAuth();
        when(auth.authorize(any(), any(), eq(Capability.EXECUTE_REPOSITORY)))
                .thenAnswer(call -> new WorkspaceAccess(
                        WORKSPACE, call.getArgument(0), MembershipRole.MEMBER, Set.of(Capability.EXECUTE_REPOSITORY)));
        var exports = exports();
        var projection = new RepositorySnapshotExports.PublicExport(
                WORKSPACE,
                new RepositorySnapshotExports.Export(UUID.randomUUID(), COMMIT, "b".repeat(64), 128),
                "c".repeat(40),
                "d".repeat(64),
                Map.of("article/index.md", "public/article.md"),
                Map.of());
        when(exports.createPublic(any(), eq(WORKSPACE))).thenReturn(projection);
        try (var peer = new Peer();
                var executor = executor(auth, exports, peer)) {
            var principal = principal();
            assertThatThrownBy(() -> client.execute(
                            executor,
                            principal,
                            WORKSPACE,
                            "historical",
                            Optional.of("c".repeat(40)),
                            "git log",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(peer.requests).isEmpty();
            var result = client.execute(
                    executor,
                    principal,
                    WORKSPACE,
                    "public",
                    Optional.empty(),
                    "git log",
                    Duration.ofSeconds(1),
                    new Cancellation());
            assertThat(result.commit()).isEqualTo(COMMIT);
            when(auth.authorize(any(), any(), eq(Capability.EXECUTE_REPOSITORY)))
                    .thenAnswer(call -> new WorkspaceAccess(
                            WORKSPACE,
                            call.getArgument(0),
                            MembershipRole.OWNER,
                            Set.of(Capability.EXECUTE_REPOSITORY, Capability.READ_PRIVATE)));
            client.execute(
                    executor,
                    principal,
                    WORKSPACE,
                    "public",
                    Optional.empty(),
                    "pwd",
                    Duration.ofSeconds(1),
                    new Cancellation());
            verify(exports, never()).create(any(), any(), any());
            verify(exports, times(1)).createPublic(principal, WORKSPACE);
            verify(exports, atLeast(3)).requireCurrentPublic(principal, WORKSPACE, projection);
            verify(exports).release(projection.export().exportId());
            assertThat(peer.operations("OPEN")).hasSize(1);
        }
    }

    @Test
    void withdrawnPublicProjectionSuppressesCompletedOutputAndClosesLease() throws Exception {
        AuthService auth = fullAuth();
        when(auth.authorize(any(), any(), eq(Capability.EXECUTE_REPOSITORY)))
                .thenAnswer(call -> new WorkspaceAccess(
                        WORKSPACE, call.getArgument(0), MembershipRole.MEMBER, Set.of(Capability.EXECUTE_REPOSITORY)));
        var exports = exports();
        var projection = new RepositorySnapshotExports.PublicExport(
                WORKSPACE,
                new RepositorySnapshotExports.Export(UUID.randomUUID(), COMMIT, "b".repeat(64), 128),
                "c".repeat(40),
                "d".repeat(64),
                Map.of(),
                Map.of());
        when(exports.createPublic(any(), any())).thenReturn(projection);
        try (var peer = new Peer();
                var executor = executor(auth, exports, peer)) {
            doAnswer(call -> {
                        if (!peer.operations("EXEC").isEmpty()) {
                            throw new SecurityException("publication withdrawn");
                        }
                        return null;
                    })
                    .when(exports)
                    .requireCurrentPublic(any(), any(), any());
            assertThatThrownBy(() -> client.execute(
                            executor,
                            principal(),
                            WORKSPACE,
                            "public",
                            Optional.empty(),
                            "cat article/index.md",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(ExecutionUnconfirmedException.class);
            assertThat(peer.operations("EXEC")).hasSize(1);
            assertThat(peer.operations("CLOSE")).isNotEmpty();
        }
    }

    @Test
    void peerShutdownStillReportsAssertionsFromActualSocketHandling() throws Exception {
        var peer = new Peer();
        peer.assertOnHelloShutdown = true;
        var exchange = CompletableFuture.runAsync(
                () -> assertThatThrownBy(() -> peer.client().hello()).isInstanceOf(WorkerUnavailableException.class));
        try {
            assertThat(peer.openEntered.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            assertThatThrownBy(peer::close)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("peer assertion after shutdown");
        }
        exchange.get(5, TimeUnit.SECONDS);
    }

    @Test
    void signsExactFramesPinsEachClientAndReleasesExportsAfterOpening() throws Exception {
        var auth = fullAuth();
        var exports = exports();
        var principal = principal();
        try (var peer = new Peer();
                var executor = executor(auth, exports, peer)) {
            var first = client.execute(
                    executor,
                    principal,
                    WORKSPACE,
                    "client-one",
                    Optional.empty(),
                    "git log -1",
                    Duration.ofSeconds(1),
                    new Cancellation());
            assertThat(first.commit()).isEqualTo(COMMIT);
            assertThat(first.stdout()).isEqualTo("fixture result");
            client.execute(
                    executor,
                    principal,
                    WORKSPACE,
                    "client-one",
                    Optional.empty(),
                    "git status",
                    Duration.ofSeconds(1),
                    new Cancellation());
            client.execute(
                    executor,
                    principal,
                    WORKSPACE,
                    "client-two",
                    Optional.empty(),
                    "git status",
                    Duration.ofSeconds(1),
                    new Cancellation());
            assertThat(peer.operations("OPEN")).hasSize(1);
            assertThat(peer.operations("EXEC")).hasSize(3);
            assertThat(peer.operations("OPEN").stream()
                            .map(node -> node.path("serverSessionHash").stringValue())
                            .distinct())
                    .hasSize(1);
            assertThat(peer.operations("EXEC").get(0).path("leaseId"))
                    .isEqualTo(peer.operations("EXEC").get(1).path("leaseId"));
            assertThatThrownBy(() -> client.execute(
                            executor,
                            principal,
                            WORKSPACE,
                            "client-one",
                            Optional.of("b".repeat(40)),
                            "pwd",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("working-copy baseline");
            verify(exports, times(1)).release(any());
            verify(auth, atLeast(3))
                    .authorize(principal, WORKSPACE, Capability.READ_PRIVATE, Capability.EXECUTE_REPOSITORY);
            assertThat(peer.failure.get()).isNull();
        }
    }

    @Test
    void renewsWhileOpenIsPendingAndCancellationWaitsPastClosingWithoutExecuting() throws Exception {
        var auth = fullAuth();
        var exports = exports();
        try (var peer = new Peer();
                var executor = executor(auth, exports, peer)) {
            peer.blockOpen = true;
            peer.closeNeedsPolling = true;
            var cancellation = new Cancellation();
            var run = CompletableFuture.runAsync(() -> client.execute(
                    executor,
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
            assertThatThrownBy(() -> run.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ExecutionAdmissionException.class);
            assertThat(peer.operations("EXEC")).isEmpty();
            verify(exports).release(any());
            assertThat(peer.failure.get()).isNull();
        }
    }

    @Test
    void lostExecReplyIsNeverReplayedAndLeavesTheSessionUnusable() throws Exception {
        var principal = principal();
        try (var peer = new Peer();
                var executor = executor(fullAuth(), exports(), peer)) {
            peer.dropExec = true;
            assertThatThrownBy(() -> client.execute(
                            executor,
                            principal,
                            WORKSPACE,
                            "lost",
                            Optional.empty(),
                            "touch local.txt",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(ExecutionUnconfirmedException.class);
            assertThat(peer.operations("EXEC")).hasSize(1);
            assertThat(peer.operations("CLOSE")).isNotEmpty();
            assertThatThrownBy(() -> executor.execute(
                            principal,
                            WORKSPACE,
                            "lost",
                            new RepositoryExecutor.CopyRequest(UUID.randomUUID().toString()),
                            Optional.empty(),
                            "touch local.txt",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(SessionReplacedException.class);
            assertThat(peer.operations("EXEC")).hasSize(1);
        }
    }

    @Test
    void revocationSendsWorkspaceTombstonesAndStopsActiveLeases() throws Exception {
        var principal = principal();
        var auth = fullAuth();
        try (var peer = new Peer();
                var executor = executor(auth, exports(), peer)) {
            client.execute(
                    executor,
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
            when(auth.authorize(principal, WORKSPACE, Capability.EXECUTE_REPOSITORY))
                    .thenThrow(new AuthException(AuthException.Code.DENIED));
            assertThatThrownBy(() -> client.execute(
                            executor,
                            principal,
                            WORKSPACE,
                            "revoked",
                            Optional.empty(),
                            "pwd",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(AuthException.class);
            executor.revoked(new AuthRevocation(WORKSPACE, Set.of(UUID.randomUUID()), Set.of()));
            assertThat(peer.operations("REVOKE")).hasSize(2);
        }
    }

    @Test
    void unauthorizedOrAlreadyCancelledCallsNeverContactTheWorker() throws Exception {
        var auth = fullAuth();
        var principal = principal();
        try (var peer = new Peer();
                var executor = executor(auth, exports(), peer)) {
            var cancelled = new Cancellation();
            cancelled.cancel();
            assertThatThrownBy(() -> client.execute(
                            executor,
                            principal,
                            WORKSPACE,
                            "cancelled",
                            Optional.empty(),
                            "pwd",
                            Duration.ofSeconds(1),
                            cancelled))
                    .isInstanceOf(ExecutionAdmissionException.class);
            doThrow(new SecurityException("denied"))
                    .when(auth)
                    .authorize(principal, WORKSPACE, Capability.READ_PRIVATE, Capability.EXECUTE_REPOSITORY);
            assertThatThrownBy(() -> client.execute(
                            executor,
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

    @Test
    void accountDiscardKeepsOwnershipAndRequiresConfirmedContainment() throws Exception {
        var actor = principal();
        try (var peer = new Peer();
                var executor = executor(fullAuth(), exports(), peer)) {
            var first = client.execute(
                    executor,
                    actor,
                    WORKSPACE,
                    "discard",
                    Optional.empty(),
                    "pwd",
                    Duration.ofSeconds(2),
                    new Cancellation());
            var request = new RepositoryExecutor.DiscardRequest(first.copyId());
            assertThat(executor.discard(principal(), WORKSPACE, request, new Cancellation())
                            .status())
                    .isEqualTo(RepositoryExecutor.DiscardStatus.ABSENT);
            assertThat(executor.discard(actor, WorkspaceId.random(), request, new Cancellation())
                            .status())
                    .isEqualTo(RepositoryExecutor.DiscardStatus.ABSENT);
            assertThat(peer.operations("CLOSE")).isEmpty();
            peer.dropClose = true;
            assertThatThrownBy(() -> executor.discard(actor, WORKSPACE, request, new Cancellation()))
                    .isInstanceOf(ExecutionAdmissionException.class);
            assertThatThrownBy(() -> executor.execute(
                            actor,
                            WORKSPACE,
                            "discard",
                            new RepositoryExecutor.CopyRequest("new"),
                            Optional.empty(),
                            "must not run",
                            Duration.ofSeconds(2),
                            new Cancellation()))
                    .isInstanceOf(ExecutionAdmissionException.class);
            assertThat(peer.operations("OPEN")).hasSize(1);
            peer.dropClose = false;
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (true) {
                try {
                    assertThat(executor.discard(actor, WORKSPACE, request, new Cancellation())
                                    .status())
                            .isEqualTo(RepositoryExecutor.DiscardStatus.DISCARDED);
                    break;
                } catch (ExecutionAdmissionException pending) {
                    if (System.nanoTime() >= deadline) {
                        throw pending;
                    }
                    Thread.sleep(20);
                }
            }
            assertThat(executor.discard(actor, WORKSPACE, request, new Cancellation())
                            .status())
                    .isEqualTo(RepositoryExecutor.DiscardStatus.ABSENT);
        }
    }

    @Test
    void disposalWaitsForTheActiveCommandBeforeRemovingTheCopy() throws Exception {
        var actor = principal();
        try (var peer = new Peer();
                var executor = executor(fullAuth(), exports(), peer)) {
            var first = command(executor, actor, "new");
            peer.holdExecReply = true;
            var running = CompletableFuture.supplyAsync(() -> command(executor, actor, first.copyId()));
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (peer.operations("EXEC").size() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(peer.operations("EXEC")).hasSize(2);
            var discard = CompletableFuture.supplyAsync(() -> executor.discard(
                    actor, WORKSPACE, new RepositoryExecutor.DiscardRequest(first.copyId()), new Cancellation()));
            assertThat(discard).isNotDone();
            assertThat(peer.operations("CLOSE")).isEmpty();
            peer.execReplyRelease.countDown();
            assertThat(running.get(3, TimeUnit.SECONDS).exitCode()).isZero();
            assertThat(discard.get(3, TimeUnit.SECONDS).status()).isEqualTo(RepositoryExecutor.DiscardStatus.DISCARDED);
        }
    }

    private static RepositoryExecutor.ExecutionResult command(
            IsolatedRepositoryExecutor executor, AuthPrincipal actor, String id) {
        return executor.execute(
                actor,
                WORKSPACE,
                "transport",
                new RepositoryExecutor.CopyRequest(id),
                Optional.empty(),
                "pwd",
                Duration.ofSeconds(2),
                new Cancellation());
    }

    private static AuthService fullAuth() {
        AuthService auth = mock(AuthService.class);
        when(auth.authorize(any(), any(), eq(Capability.EXECUTE_REPOSITORY)))
                .thenAnswer(call -> new WorkspaceAccess(
                        call.getArgument(1),
                        call.getArgument(0),
                        MembershipRole.OWNER,
                        Set.of(Capability.READ_PRIVATE, Capability.EXECUTE_REPOSITORY)));
        return auth;
    }

    private static IsolatedRepositoryExecutor executor(AuthService auth, RepositorySnapshotExports exports, Peer peer) {
        return new IsolatedRepositoryExecutor(
                peer.accounts.store(),
                mock(PortableContentExports.class),
                mock(MediaFileService.class),
                mock(SelectedFileSaves.class),
                auth,
                exports,
                peer.client(),
                8,
                Duration.ofSeconds(8),
                Duration.ofSeconds(3));
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
                var executor = executor(fullAuth(), exports, peer)) {
            var cancellation = new Cancellation();
            var run = CompletableFuture.runAsync(() -> client.execute(
                    executor,
                    principal(),
                    WORKSPACE,
                    "exporting",
                    Optional.empty(),
                    "pwd",
                    Duration.ofSeconds(1),
                    cancellation));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                cancellation.cancel();
            } finally {
                release.countDown();
            }
            assertThatThrownBy(() -> run.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ExecutionAdmissionException.class);
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
            assertThatThrownBy(() ->
                            client.request(hello, identity, "EXEC", new WorkerRequests.Renew(), Duration.ofSeconds(1)))
                    .isInstanceOf(WorkerUnavailableException.class);
            peer.wrongRequestId = false;
            peer.stallExec = true;
            long start = System.nanoTime();
            assertThatThrownBy(() ->
                            client.request(hello, identity, "EXEC", new WorkerRequests.Renew(), Duration.ofMillis(100)))
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
    void cancellingAQueuedRequestDoesNotCancelTheCurrentWriter() throws Exception {
        var actor = principal();
        try (var peer = new Peer();
                var executor = executor(fullAuth(), exports(), peer)) {
            peer.holdExecReply = true;
            var running = CompletableFuture.supplyAsync(() -> command(executor, actor, "new"));
            assertThat(peer.execEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var cancellation = new Cancellation();
            var queued = CompletableFuture.supplyAsync(() -> executor.execute(
                    actor,
                    WORKSPACE,
                    "queued",
                    new RepositoryExecutor.CopyRequest("new"),
                    Optional.empty(),
                    "must-not-run",
                    Duration.ofSeconds(2),
                    cancellation));
            cancellation.cancel();
            assertThatThrownBy(() -> queued.get(2, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ExecutionAdmissionException.class);
            assertThat(peer.operations("CLOSE")).isEmpty();
            peer.execReplyRelease.countDown();
            assertThat(running.get(3, TimeUnit.SECONDS).exitCode()).isZero();
            assertThat(peer.operations("EXEC")).hasSize(1);
        }
    }

    @Test
    void closedBridgeDoesNotReplaceTheConfirmedCancelledCommandResult() throws Exception {
        try (var peer = new Peer();
                var executor = executor(fullAuth(), exports(), peer)) {
            peer.stallExec = true;
            peer.terminationReason = "cancelled";
            var cancellation = new Cancellation();
            var running = CompletableFuture.supplyAsync(() -> client.execute(
                    executor,
                    principal(),
                    WORKSPACE,
                    "cancel-bridge",
                    Optional.empty(),
                    "sleep 10",
                    Duration.ofSeconds(2),
                    cancellation));
            assertThat(peer.execEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(peer.bridgeEntered.await(5, TimeUnit.SECONDS)).isTrue();
            cancellation.cancel();
            assertThat(running.get(5, TimeUnit.SECONDS).terminationReason())
                    .isEqualTo(RepositoryExecutor.TerminationReason.CANCELLED);
        }
    }

    @Test
    void workerLeaseAndLifecycleReasonsMapToExplicitPortResults() throws Exception {
        try (var peer = new Peer();
                var executor = executor(fullAuth(), exports(), peer)) {
            for (String reason : List.of("session_closed", "client_shutdown", "lease_expired", "sandbox_failed")) {
                peer.terminationReason = reason;
                var result = client.execute(
                        executor,
                        principal(),
                        WORKSPACE,
                        reason,
                        Optional.empty(),
                        "pwd",
                        Duration.ofSeconds(1),
                        new Cancellation());
                assertThat(result.terminationReason())
                        .isEqualTo(
                                Set.of("lease_expired", "sandbox_failed").contains(reason)
                                        ? RepositoryExecutor.TerminationReason.SANDBOX_FAILURE
                                        : RepositoryExecutor.TerminationReason.CANCELLED);
            }
            assertThat(peer.operations("CLOSE")).hasSize(4);
        }
    }

    @Test
    void confirmedCommandCancellationReattachesTheSameCopy() throws Exception {
        var actor = principal();
        try (var peer = new Peer();
                var executor = executor(fullAuth(), exports(), peer)) {
            peer.terminationReason = "cancelled";
            var first = command(executor, actor, "new");
            assertThat(first.terminationReason()).isEqualTo(RepositoryExecutor.TerminationReason.CANCELLED);
            peer.terminationReason = "normal";
            var resumed = command(executor, actor, first.copyId());
            assertThat(resumed.copyId()).isEqualTo(first.copyId());
            assertThat(resumed.retention().resumed()).isTrue();
            assertThat(peer.operations("OPEN")).hasSize(1);
            assertThat(peer.operations("ATTACH")).hasSize(1);
        }
    }

    @Test
    void refusedReattachmentPreservesTheCopyAndReportsAdmissionReason() throws Exception {
        for (String code : List.of("SESSION_CAPACITY", "REQUEST_CAPACITY", "COPY_BUSY", "SESSION_BUSY")) {
            var actor = principal();
            try (var peer = new Peer();
                    var executor = executor(fullAuth(), exports(), peer)) {
                peer.terminationReason = "cancelled";
                var first = command(executor, actor, "new");
                peer.terminationReason = "normal";
                peer.attachRefusal = code;
                assertThatThrownBy(() -> command(executor, actor, first.copyId()))
                        .isInstanceOfSatisfying(ExecutionAdmissionException.class, error -> {
                            assertThat(error.reason())
                                    .isEqualTo(
                                            code.endsWith("CAPACITY")
                                                    ? ExecutionAdmissionException.Reason.CAPACITY
                                                    : ExecutionAdmissionException.Reason.BUSY);
                            assertThat(error.recoveryAvailable()).isTrue();
                        });
                assertThat(peer.operations("EXEC")).hasSize(1);
                peer.attachRefusal = null;
                assertThat(command(executor, actor, first.copyId()).copyId()).isEqualTo(first.copyId());
                assertThat(peer.operations("OPEN")).hasSize(1);
            }
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
                        peer.accounts.store(),
                        mock(PortableContentExports.class),
                        mock(MediaFileService.class),
                        mock(SelectedFileSaves.class),
                        fullAuth(),
                        exports,
                        peer.client(),
                        1,
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> client.execute(
                            executor,
                            principal(),
                            WORKSPACE,
                            "failed-A",
                            Optional.empty(),
                            "pwd",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(ExecutionAdmissionException.class);
            assertThat(client.execute(
                                    executor,
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
    void unconfirmedDisposalRetainsAdmissionUntilContainmentOrWorkerRestart() throws Exception {
        for (String failure : List.of("closing", "missing", "restart")) {
            var actor = principal();
            try (var peer = new Peer();
                    var executor = new IsolatedRepositoryExecutor(
                            peer.accounts.store(),
                            mock(PortableContentExports.class),
                            mock(MediaFileService.class),
                            mock(SelectedFileSaves.class),
                            fullAuth(),
                            exports(),
                            peer.client(),
                            1,
                            Duration.ofSeconds(3),
                            Duration.ofSeconds(1))) {
                var first = command(executor, actor, "new");
                var discard = new RepositoryExecutor.DiscardRequest(first.copyId());
                peer.closeForever = failure.equals("closing");
                peer.dropClose = !peer.closeForever;
                assertThatThrownBy(() -> executor.discard(actor, WORKSPACE, discard, new Cancellation()))
                        .isInstanceOf(ExecutionAdmissionException.class);
                assertThatThrownBy(() -> command(executor, principal(), "new"))
                        .isInstanceOf(ExecutionAdmissionException.class);
                assertThat(peer.operations("OPEN")).hasSize(1);
                peer.closeForever = false;
                peer.dropClose = false;
                if (failure.equals("restart")) {
                    peer.boot = UUID.randomUUID();
                    peer.states.clear();
                }
                assertThat(executor.discard(actor, WORKSPACE, discard, new Cancellation())
                                .status())
                        .isEqualTo(RepositoryExecutor.DiscardStatus.DISCARDED);
                assertThat(command(executor, principal(), "new").exitCode()).isZero();
                assertThat(peer.operations("OPEN")).hasSize(2);
            }
        }
    }

    @Test
    void workerRestartAndGrantChangeRetainCopyIdentityAtAnyCapacity() throws Exception {
        for (int capacity : List.of(1, 4)) {
            var actor = principal();
            try (var peer = new Peer();
                    var executor = new IsolatedRepositoryExecutor(
                            peer.accounts.store(),
                            mock(PortableContentExports.class),
                            mock(MediaFileService.class),
                            mock(SelectedFileSaves.class),
                            fullAuth(),
                            exports(),
                            peer.client(),
                            capacity,
                            Duration.ofSeconds(3),
                            Duration.ofSeconds(1))) {
                var first = command(executor, actor, "new");
                peer.boot = UUID.randomUUID();
                peer.states.clear();
                var reconnected = command(executor, actor, first.copyId());
                assertThat(reconnected.copyId()).isEqualTo(first.copyId());
                assertThat(reconnected.retention().resumed()).isTrue();
                var grant = principal();
                UUID account = actor.accountId();
                when(grant.accountId()).thenReturn(account);
                assertThat(command(executor, grant, first.copyId()).copyId()).isEqualTo(first.copyId());
                assertThat(peer.operations("OPEN")).hasSize(1);
                assertThat(peer.operations("ATTACH")).hasSize(2);
                assertThat(peer.operations("EXEC")).hasSize(3);
            }
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
                var executor = executor(fullAuth(), exports(), peer)) {
            peer.stdout = "\uFFFD".repeat(65536);
            var result = client.execute(
                    executor,
                    principal(),
                    WORKSPACE,
                    "replacement",
                    Optional.empty(),
                    "pwd",
                    Duration.ofSeconds(1),
                    new Cancellation());
            assertThat(result.stdout()).isEqualTo(peer.stdout);
            peer.stdout += "\uFFFD";
            assertThatThrownBy(() -> client.execute(
                            executor,
                            principal(),
                            WORKSPACE,
                            "too-large",
                            Optional.empty(),
                            "pwd",
                            Duration.ofSeconds(1),
                            new Cancellation()))
                    .isInstanceOf(ExecutionUnconfirmedException.class);
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
        private final SocketAccountJournal accounts = new SocketAccountJournal();
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
        private final CountDownLatch execEntered = new CountDownLatch(1);
        private final CountDownLatch execReplyRelease = new CountDownLatch(1);
        private volatile boolean holdExecReply;
        private final CountDownLatch bridgeEntered = new CountDownLatch(1);
        private final CountDownLatch bridgeCompleted = new CountDownLatch(1);
        private final AtomicBoolean bridgeDelivered = new AtomicBoolean();
        private volatile Map<String, ?> bridgeCommand;
        private volatile String executionId;
        private volatile boolean blockOpen;
        private volatile boolean closeNeedsPolling;
        private volatile boolean closeForever;
        private volatile boolean dropClose;
        private volatile boolean dropHello;
        private volatile boolean assertOnHelloShutdown;
        private volatile boolean dropExec;
        private volatile boolean oversizedHello;
        private volatile int codeActProtocol = 1;
        private volatile int artifactProtocol = 1;
        private volatile int moveProtocol = 1;
        private volatile int exportProtocol = 1;
        private volatile boolean wrongRequestId;
        private volatile boolean stallExec;
        private volatile boolean executionCapacity;
        private volatile boolean baselineUnavailable;
        private volatile String terminationReason = "normal";
        private volatile String attachRefusal;
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

        private void respond(SocketChannel connection) {
            try (connection;
                    var input = Channels.newInputStream(connection);
                    var output = new DataOutputStream(Channels.newOutputStream(connection))) {
                byte[] prefix = input.readNBytes(4);
                if (prefix.length == 0) {
                    // A client exchange closes its socket without writing when its deadline elapses
                    // or its thread is interrupted, which executor shutdown does to renewals still
                    // in flight, before this peer is closed. That connection carries no request and
                    // nothing to answer. A prefix that arrives short is a truncated frame and fails.
                    return;
                }
                assertThat(prefix).as("worker frame length prefix").hasSize(4);
                int length = ByteBuffer.wrap(prefix).getInt();
                assertThat(length).isBetween(1, WorkerClient.MAX_FRAME);
                JsonNode envelope = json.readTree(input.readNBytes(length));
                Object response;
                if (envelope.path("operation").asString("").equals("HELLO")) {
                    if (assertOnHelloShutdown) {
                        openEntered.countDown();
                        try {
                            openRelease.await(8, TimeUnit.SECONDS);
                        } finally {
                            fail("peer assertion after shutdown");
                        }
                    }
                    if (dropHello) {
                        return;
                    }
                    if (oversizedHello) {
                        output.writeInt(WorkerClient.MAX_FRAME + 1);
                        output.flush();
                        return;
                    }
                    var hello = new LinkedHashMap<String, Object>(Map.of(
                            "ok",
                            true,
                            "version",
                            1,
                            "codeActProtocol",
                            codeActProtocol,
                            "artifactProtocol",
                            artifactProtocol,
                            "moveProtocol",
                            moveProtocol,
                            "exportProtocol",
                            exportProtocol,
                            "workerBootId",
                            boot,
                            "maxFrameBytes",
                            WorkerClient.MAX_FRAME,
                            "leaseSeconds",
                            10,
                            "renewAfterSeconds",
                            1));
                    hello.put("diskCopyProtocol", 1);
                    hello.put("gitBaselineProtocol", 1);
                    response = hello;
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
                    if (response == null) {
                        return;
                    }
                }
                byte[] bytes = json.writeValueAsBytes(response);
                try {
                    output.writeInt(bytes.length);
                    output.write(bytes);
                    output.flush();
                } catch (IOException disconnected) {
                    // A stale-boot poll can reject first and cancel the simultaneous EXEC socket.
                    // Only that obsolete reply may lose its receiver; parsing and assertions still fail.
                    if (!(response instanceof Map<?, ?> fields) || !"WORKER_RESTARTED".equals(fields.get("code"))) {
                        throw disconnected;
                    }
                }
            } catch (Throwable exception) {
                recordFailure(exception);
            }
        }

        private void recordFailure(Throwable exception) {
            if (exception instanceof IOException && (closed.get() || stallExec)) {
                return;
            }
            if (exception instanceof InterruptedException && closed.get()) {
                return;
            }
            failure.compareAndSet(null, exception);
        }

        private Object handle(JsonNode request) throws Exception {
            String operation = request.path("operation").stringValue();
            String lease = request.path("leaseId").stringValue();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", true);
            response.put(
                    "requestId",
                    wrongRequestId
                            ? UUID.randomUUID().toString()
                            : request.path("requestId").stringValue());
            response.put("leaseId", lease);
            response.put("commit", COMMIT);
            response.put("gitCommit", COMMIT);
            response.put("state", "READY");
            switch (operation) {
                case "BASELINE" -> {
                    response.put("ok", !baselineUnavailable);
                    if (baselineUnavailable) {
                        response.put("code", "BASELINE_UNAVAILABLE");
                    } else {
                        response.put(
                                "gitCommit", request.path("data").path("commit").stringValue());
                    }
                }
                case "DISCARD" -> {
                    response.put("state", "DISCARDED");
                    response.put("copyId", request.path("data").path("copyId").stringValue());
                }
                case "OPEN", "ATTACH" -> {
                    if (operation.equals("ATTACH") && attachRefusal != null) {
                        response.put("ok", false);
                        response.put("code", attachRefusal);
                        break;
                    }
                    states.put(lease, "INITIALIZING");
                    openEntered.countDown();
                    if (blockOpen) {
                        assertThat(openRelease.await(8, TimeUnit.SECONDS)).isTrue();
                    }
                    states.putIfAbsent(lease, "READY");
                }
                case "RENEW" -> {
                    renewEntered.countDown();
                    response.put("state", states.getOrDefault(lease, "INITIALIZING"));
                }
                case "BRIDGE_POLL" -> {
                    bridgeEntered.countDown();
                    Thread.sleep(50);
                    response.put("bridgeRequest", null);
                    if (bridgeCommand != null && executionId != null && bridgeDelivered.compareAndSet(false, true)) {
                        response.put("executionId", executionId);
                        response.put("bridgeRequest", bridgeCommand);
                    }
                    if (states.getOrDefault(lease, "").equals("CLOSED")) {
                        response.put("ok", false);
                        response.put("code", "BRIDGE_UNAVAILABLE");
                    }
                }
                case "EXEC" -> {
                    if (executionCapacity) {
                        response.put("ok", false);
                        response.put("code", "EXECUTION_CAPACITY");
                        break;
                    }
                    executionId = request.path("data").path("executionId").asString("");
                    execEntered.countDown();
                    if (holdExecReply && !execReplyRelease.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test did not release the execution reply");
                    }
                    if (bridgeCommand != null) {
                        assertThat(bridgeCompleted.await(5, TimeUnit.SECONDS)).isTrue();
                    }
                    if (dropExec) {
                        return null;
                    }
                    if (stallExec) {
                        Thread.sleep(500);
                    }
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
                                    terminationReason,
                                    "artifacts",
                                    Map.of(),
                                    "artifactErrors",
                                    Map.of()));
                    if (Set.of(
                                    "cancelled",
                                    "session_closed",
                                    "client_shutdown",
                                    "revoked",
                                    "lease_expired",
                                    "sandbox_failed")
                            .contains(terminationReason)) {
                        response.put("state", "CLOSED");
                        states.put(lease, "CLOSED");
                    }
                }
                case "BRIDGE_COMPLETE" -> bridgeCompleted.countDown();
                case "CLOSE" -> {
                    if (dropClose) {
                        return null;
                    }
                    String state = closeForever
                                    || closeNeedsPolling
                                            && !states.getOrDefault(lease, "").equals("CLOSING")
                            ? "CLOSING"
                            : "CLOSED";
                    states.put(lease, state);
                    response.put("state", state);
                    if (state.equals("CLOSED")) {
                        openRelease.countDown();
                    }
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
