package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthRevocation;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.McpSessionClosed;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Synthetic authority/authentication fixture; all execution uses the production adapter and actual root worker. */
public final class ExecutorNativeProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JsonNode config;
    private final WorkspaceId workspace = WorkspaceId.random();
    private final AuthService auth = mock(AuthService.class);
    private final AuthPrincipal principal = principal();
    private final AtomicInteger released = new AtomicInteger();
    private final RepositorySnapshotExports exports;
    private int tests;

    private ExecutorNativeProbe(Path configuration) throws Exception {
        config = JSON.readTree(Files.readString(configuration));
        Path master = path("bundle");
        String commit = config.path("commit").stringValue();
        exports = new RepositorySnapshotExports() {
            @Override
            public Export create(AuthPrincipal actor, WorkspaceId selected, Optional<String> requested) {
                try {
                    if (requested.isPresent() && !requested.get().equals(commit)) throw new IllegalArgumentException();
                    UUID id = UUID.randomUUID();
                    Path target = path("exports").resolve(id + ".bundle");
                    Files.copy(master, target);
                    return new Export(id, commit, hash(Files.readAllBytes(target)), Files.size(target));
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }

            @Override
            public void release(UUID id) {
                try {
                    Files.delete(path("exports").resolve(id + ".bundle"));
                    released.incrementAndGet();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }
        };
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !System.getProperty("os.name").equalsIgnoreCase("Linux"))
            throw new IllegalArgumentException();
        var probe = new ExecutorNativeProbe(Path.of(args[0]));
        if (args[1].equals("abandon")) probe.abandon();
        else if (args[1].equals("main")) probe.run();
        else throw new IllegalArgumentException();
    }

    private IsolatedRepositoryExecutor adapter(Path socket) {
        return new ExecutorConfiguration()
                .isolatedRepositoryExecutor(auth, exports, JSON, socket, path("privateKey"), 8, 45, 8);
    }

    private void run() throws Exception {
        try (var rejected = adapter(path("fakeSocket"))) {
            assertThatThrownBy(() -> execute(rejected, "wrong-peer", "pwd", new Cancellation()))
                    .isInstanceOf(WorkerUnavailableException.class);
        }
        passed("root-owned-socket-rejects-non-root-peer");
        byte[] originalBundle = Files.readAllBytes(path("bundle"));
        try (var executor = adapter(path("socket"))) {
            long start = System.nanoTime();
            var first = execute(
                    executor, "first", "git rev-parse HEAD; git log --oneline; cat article.md", new Cancellation());
            assertThat(first.exitCode()).isZero();
            assertThat(first.commit()).isEqualTo(config.path("commit").stringValue());
            assertThat(first.stdout()).contains(first.commit(), "Synthetic native history", "searchable");
            passed("signed-open-through-production-permission-checks", "milliseconds", millis(start));
            start = System.nanoTime();
            for (int i = 0; i < 20; i++)
                assertThat(execute(executor, "first", "git rev-parse HEAD; test -f article.md", new Cancellation())
                                .exitCode())
                        .isZero();
            passed("twenty-adapter-session-reuses", "meanMilliseconds", millis(start) / 20.0);
            assertThat(execute(executor, "first", "printf isolated > only-first", new Cancellation())
                            .exitCode())
                    .isZero();
            assertThat(execute(executor, "second", "test ! -e only-first", new Cancellation())
                            .exitCode())
                    .isZero();
            assertThatThrownBy(() -> executor.execute(
                            principal,
                            workspace,
                            "first",
                            Optional.of("f".repeat(40)),
                            "pwd",
                            Duration.ofSeconds(2),
                            new Cancellation()))
                    .isInstanceOf(IllegalArgumentException.class);
            passed("same-key-clients-have-separate-pinned-workdirs");
            assertThat(execute(
                                    executor,
                                    "second",
                                    "rm -rf .git/objects; printf changed > article.md",
                                    new Cancellation())
                            .exitCode())
                    .isZero();
            assertThat(Files.readAllBytes(path("bundle"))).isEqualTo(originalBundle);
            control("assert-source-unchanged");
            passed("command-mutations-preserve-authority-bundle-and-source");
            close(executor, "first");
            close(executor, "second");

            var cancel = new Cancellation();
            var cancelled = CompletableFuture.supplyAsync(() -> execute(executor, "cancel", descendant(), cancel));
            control("await-descendant");
            cancel.cancel();
            assertThat(cancelled.get(20, TimeUnit.SECONDS).terminationReason())
                    .isEqualTo(RepositoryExecutor.TerminationReason.CANCELLED);
            control("assert-no-processes");
            passed("callback-cancellation-kills-detached-descendants");
            close(executor, "cancel");

            var revoked =
                    CompletableFuture.supplyAsync(() -> execute(executor, "revoke", descendant(), new Cancellation()));
            control("await-descendant");
            executor.revoked(new AuthRevocation(workspace, Set.of(), Set.of(principal.subjectId())));
            var reason = revoked.get(20, TimeUnit.SECONDS).terminationReason();
            assertThat(reason)
                    .isIn(RepositoryExecutor.TerminationReason.REVOKED, RepositoryExecutor.TerminationReason.CANCELLED);
            control("assert-no-processes");
            passed("revocation-closes-active-process-tree", "reason", reason);
            close(executor, "revoke");
        }

        // A different synthetic key avoids reusing the deliberately revoked key's worker tombstone.
        var secondPrincipal = principal();
        try (var executor = adapter(path("socket"))) {
            var active = CompletableFuture.supplyAsync(() -> executor.execute(
                    secondPrincipal,
                    workspace,
                    "restart",
                    Optional.empty(),
                    descendant(),
                    Duration.ofSeconds(25),
                    new Cancellation()));
            control("await-descendant");
            control("restart-worker");
            assertThatThrownBy(() -> active.get(25, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(WorkerUnavailableException.class);
            assertThatThrownBy(() -> executor.execute(
                            secondPrincipal,
                            workspace,
                            "restart",
                            Optional.empty(),
                            "pwd",
                            Duration.ofSeconds(2),
                            new Cancellation()))
                    .isInstanceOf(WorkerUnavailableException.class);
            control("assert-no-processes");
            assertThat(executor.execute(
                                    secondPrincipal,
                                    workspace,
                                    "new-after-restart",
                                    Optional.empty(),
                                    "git rev-parse HEAD",
                                    Duration.ofSeconds(3),
                                    new Cancellation())
                            .exitCode())
                    .isZero();
            passed("worker-restart-invalidates-old-lease-and-allows-new-session");
        }
        control("assert-no-processes");
        assertThat(released.get()).isGreaterThanOrEqualTo(6);
        System.out.println(JSON.writeValueAsString(Map.of(
                "summary",
                "PASS",
                "tests",
                tests,
                "authentication",
                "synthetic-stub-not-PG-or-MCP-acceptance",
                "adapterClassSha256",
                classHash(IsolatedRepositoryExecutor.class),
                "nativeProbeClassSha256",
                classHash(ExecutorNativeProbe.class))));
    }

    private void abandon() throws Exception {
        var executor = adapter(path("socket"));
        CompletableFuture.runAsync(() -> execute(executor, "abandoned", descendant(), new Cancellation()));
        control("await-descendant");
        System.out.println(JSON.writeValueAsString(Map.of("abandon", "READY")));
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }

    private RepositoryExecutor.ExecutionResult execute(
            IsolatedRepositoryExecutor executor, String session, String command, Cancellation cancellation) {
        return executor.execute(
                principal, workspace, session, Optional.empty(), command, Duration.ofSeconds(25), cancellation);
    }

    private void close(IsolatedRepositoryExecutor executor, String session) {
        executor.closed(
                new McpSessionClosed(workspace, principal.subjectId(), session, McpSessionClosed.Reason.CLIENT_DELETE));
    }

    private static String descendant() {
        return "python3 -c \"import subprocess,time; subprocess.Popen(['sleep','60'],start_new_session=True); print('native-child-ready',flush=True); time.sleep(60)\"";
    }

    private void control(String operation) throws Exception {
        Path directory = path("control");
        String id = UUID.randomUUID().toString();
        Path temporary = directory.resolve("request.tmp");
        Files.writeString(temporary, JSON.writeValueAsString(Map.of("operation", operation, "id", id)));
        Files.move(temporary, directory.resolve("request.json"), StandardCopyOption.ATOMIC_MOVE);
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (System.nanoTime() < deadline) {
            Path response = directory.resolve("response.json");
            if (Files.exists(response)) {
                JsonNode result = JSON.readTree(Files.readString(response));
                if (result.path("id").asString("").equals(id)) {
                    Files.delete(response);
                    assertThat(result.path("ok").booleanValue())
                            .withFailMessage(result.toString())
                            .isTrue();
                    return;
                }
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("Native controller did not acknowledge fixture operation");
    }

    private void passed(String name) {
        passed(name, "source", "synthetic-only");
    }

    private void passed(String name, String key, Object value) {
        tests++;
        System.out.println(JSON.writeValueAsString(Map.of("test", name, "result", "PASS", key, value)));
    }

    private Path path(String property) {
        return Path.of(config.path(property).stringValue());
    }

    private static double millis(long start) {
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private static String classHash(Class<?> type) throws Exception {
        try (var bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            return hash(bytes.readAllBytes());
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static AuthPrincipal principal() {
        var principal = mock(AuthPrincipal.class);
        when(principal.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(principal.subjectId()).thenReturn(UUID.randomUUID());
        when(principal.accountId()).thenReturn(UUID.randomUUID());
        return principal;
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
}
