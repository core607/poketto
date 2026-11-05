package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.auth.SiteGroup;
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Real native repository ownership and Git exports; only the account authority is synthetic. */
final class NativeGameRepository implements AutoCloseable {
    private static final ExecutionCancellation NEVER_CANCELLED = new ExecutionCancellation() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public Registration onCancel(Runnable terminate) {
            return () -> {};
        }
    };
    private final WorkspaceId workspace = WorkspaceId.random();
    private final AuthPrincipal actor = GameTestIdentities.player(workspace).principal();
    private final PublicExecutionNativeFixture content;
    private final NativeRepositoryExecutor executor;
    private String copy = "new";
    private int reads;

    NativeGameRepository(Path socket, Path directory) throws Exception {
        AuthService auth = mock(AuthService.class);
        var access = new WorkspaceAccess(workspace, actor, MembershipRole.OWNER, EnumSet.allOf(Capability.class));
        when(auth.authorize(any(), any())).thenReturn(access);
        when(auth.authorize(any(), any(), any(Capability[].class))).thenReturn(access);
        when(auth.siteGroup(actor)).thenReturn(SiteGroup.ADMINISTRATOR);
        doAnswer(call -> ((Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        content = new PublicExecutionNativeFixture(
                directory.resolve("authority"), directory.resolve("exports"), auth, workspace);
        executor = new ExecutorConfiguration()
                .nativeRepositoryExecutor(
                        AccountCopyTestData.disk(directory.resolve("metadata")),
                        auth,
                        content.exports(),
                        content.media(auth),
                        content.packages(auth),
                        content.reader(auth),
                        content.patches(auth),
                        content.moves(auth),
                        socket,
                        ExecutionLimits.sessions(2),
                        directory.resolve("transfers"),
                        128L << 20,
                        "repository",
                        "repository",
                        64 * 1024 * 1024,
                        8192,
                        "native");
    }

    double read(NativeGameControl control) throws Exception {
        int current = ++reads;
        long started = System.nanoTime();
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<RepositoryExecutor.ExecutionResult> result = tasks.submit(() -> executor.execute(
                    actor,
                    workspace,
                    new RepositoryExecutor.CopyRequest(copy),
                    Optional.empty(),
                    source(current),
                    Duration.ofSeconds(10),
                    NEVER_CANCELLED));
            if (current == 3) {
                control.run("game-repository-overlap");
            }
            RepositoryExecutor.ExecutionResult observed = result.get(30, TimeUnit.SECONDS);
            assertThat(observed.exitCode()).isZero();
            assertThat(observed.stdout()).contains("current-secret-needle", "retained-game-probe");
            assertThat(observed.commit()).isEqualTo(content.sourceCommit());
            if (current > 1) {
                assertThat(observed.copyId()).isEqualTo(copy);
            }
            copy = observed.copyId();
        }
        return Duration.ofNanos(System.nanoTime() - started).toNanos() / 1_000_000.0;
    }

    private static String source(int read) {
        String overlap = """
                python3 -c 'import ctypes,time; ctypes.CDLL(None).prctl(15,b"pkt-repo-live",0,0,0); time.sleep(2)'
                """;
        return (read == 1 ? "printf retained-game-probe > private/game-unsaved.txt\n" : "") + overlap
                + "cat private/secret.md private/game-unsaved.txt";
    }

    Summary finish() {
        assertThat(reads).isEqualTo(3);
        assertThat(executor.discard(actor, workspace, new RepositoryExecutor.DiscardRequest(copy), NEVER_CANCELLED)
                        .status())
                .isEqualTo(RepositoryExecutor.DiscardStatus.DISCARDED);
        return new Summary(
                "native-repository-copy-and-discard-through-public-service",
                "PASS",
                copy,
                content.sourceCommit(),
                reads);
    }

    @Override
    public void close() {
        try {
            executor.close();
        } finally {
            content.close();
        }
    }

    record Summary(String test, String result, String copyId, String commit, int reads) {}
}
