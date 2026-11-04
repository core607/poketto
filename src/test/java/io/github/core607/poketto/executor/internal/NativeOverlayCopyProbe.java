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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import tools.jackson.databind.json.JsonMapper;

/** Separate application JVMs access one journal; the controller kills the writer after the public execute call returns. */
public final class NativeOverlayCopyProbe {
    private static final UUID ACCOUNT = UUID.fromString("39739301-2670-49ab-b991-bd62c510860f");
    private static final UUID SUBJECT = UUID.fromString("093abc62-7c2c-4bc2-979c-2ebda7809cf1");
    private static final WorkspaceId WORKSPACE =
            new WorkspaceId(UUID.fromString("82b13db9-1b1f-4f0e-b7ee-2f7d676659a5"));
    private static final JsonMapper JSON = new JsonMapper();

    private NativeOverlayCopyProbe() {}

    public static void main(String[] args) throws Exception {
        Path socket = Path.of(args[0]);
        Path root = Path.of(args[1]);
        boolean full = !args[2].equals("public");
        UUID account = args[2].equals("other") ? UUID.fromString("0f453746-a87f-4d2a-af03-062c2c63c7db") : ACCOUNT;
        boolean write = args[3].equals("write");
        String profile = args.length > 4 ? args[4] : "python";
        String builder = args.length > 5 ? args[5] : profile;
        AuthService auth = mock(AuthService.class);
        AuthPrincipal actor = mock(AuthPrincipal.class);
        when(actor.accountId()).thenReturn(account);
        when(actor.subjectId()).thenReturn(SUBJECT);
        when(actor.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(actor.credentialVersion()).thenReturn(1L);
        when(auth.siteGroup(actor)).thenReturn(SiteGroup.ADMINISTRATOR);
        var capabilities = EnumSet.allOf(Capability.class);
        if (!full) {
            capabilities.remove(Capability.READ_PRIVATE);
        }
        var access = new WorkspaceAccess(WORKSPACE, actor, MembershipRole.OWNER, capabilities);
        when(auth.authorize(any(), any())).thenReturn(access);
        when(auth.authorize(any(), any(), any(Capability[].class))).thenReturn(access);
        doAnswer(call -> ((Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        Files.createDirectories(root.resolve("authority"));
        var accounts = AccountCopyTestData.disk(root.resolve("metadata"));
        boolean first = write && args[2].equals("full");
        try (var fixture = PublicExecutionNativeFixture.withExportLimit(
                        root.resolve("authority"), root.resolve("exports"), auth, WORKSPACE, !first, 16L << 20);
                var executor = new ExecutorConfiguration()
                        .nativeRepositoryExecutor(
                                accounts,
                                auth,
                                fixture.exports(),
                                fixture.media(auth),
                                fixture.packages(auth),
                                fixture.reader(auth),
                                fixture.patches(auth),
                                fixture.moves(auth),
                                socket,
                                ExecutionLimits.sessions(3),
                                root.resolve("transfers"),
                                32L << 20,
                                profile,
                                builder,
                                8L << 20,
                                256)) {
            if (first && !builder.equals(profile)) {
                byte[] contents = new byte[3 << 20];
                new Random(42).nextBytes(contents);
                fixture.seedFile("private/large-template.bin", contents);
            }
            var cancellation = mock(ExecutionCancellation.class);
            if (args[3].equals("save")) {
                String file = "private/selected.md";
                var result = executor.execute(
                        actor,
                        WORKSPACE,
                        new RepositoryExecutor.CopyRequest(RepositoryExecutor.NEW_COPY),
                        Optional.empty(),
                        "set -eu; printf 'selected change' > private/selected.md; poketto save private/selected.md",
                        Duration.ofSeconds(15),
                        cancellation);
                assertThat(result.exitCode())
                        .as(result.stderr() + result.stdout())
                        .isZero();
                assertThat(JSON.readTree(result.stdout()).path("ok").booleanValue())
                        .isTrue();
                assertThat(fixture.pushes()).isEqualTo(1);
                assertThat(fixture.reader(auth)
                                .getFile(actor, WORKSPACE, Optional.empty(), file)
                                .source())
                        .contains("selected change");
                assertThat(executor.discard(
                                        actor,
                                        WORKSPACE,
                                        new RepositoryExecutor.DiscardRequest(result.copyId()),
                                        cancellation)
                                .status())
                        .isEqualTo(RepositoryExecutor.DiscardStatus.DISCARDED);
                System.out.println("SAVED " + JSON.writeValueAsString(Map.of("pushes", fixture.pushes())));
                return;
            }

            String relative = full ? "private/secret.md" : "article/index.md";
            String copyFile = args[2] + ".copy";
            if (write) {
                var result = executor.execute(
                        actor,
                        WORKSPACE,
                        new RepositoryExecutor.CopyRequest(RepositoryExecutor.NEW_COPY),
                        Optional.empty(),
                        """
                        set -eu
                        test -f %s
                        rm %s
                        printf 'acknowledged draft' > draft
                        chmod 751 draft
                        ln draft twin
                        ln -s draft link
                        printf 'uncommitted home' > "$HOME/settings"
                        (sleep 3; printf late > detached) </dev/null >/dev/null 2>&1 &
                        printf 'overlay-written'
                        """.formatted(relative, relative),
                        Duration.ofSeconds(8),
                        cancellation);
                assertThat(result.exitCode()).as(result.stderr()).isZero();
                assertThat(result.stdout()).isEqualTo("overlay-written");
                Files.writeString(root.resolve(copyFile), result.copyId());
                var owner = new AccountCopyRecord.Owner(account, WORKSPACE.value(), full);
                try (var held = new AccountCommand(accounts, owner, cancellation)) {
                    var storage = held.record().writer().storage();
                    assertThat(storage.volumeId()).isNull();
                    assertThat(storage.upper()).isNotNull();
                    System.out.println("ACKNOWLEDGED "
                            + JSON.writeValueAsString(Map.of(
                                    "copy",
                                    result.copyId(),
                                    "template",
                                    storage.templateId(),
                                    "upper",
                                    storage.upper())));
                    System.out.flush();
                }
                new CountDownLatch(1).await();
            } else {
                String copy = Files.readString(root.resolve(copyFile));
                String check = """
                        set -eu
                        test ! -e %s
                        test ! -e detached
                        test "$(cat "$HOME/settings")" = 'uncommitted home'
                        test "$(cat draft)" = 'acknowledged draft'
                        test "$(stat -c %%a draft)" = 751
                        test "$(stat -c %%i draft)" = "$(stat -c %%i twin)"
                        test "$(readlink link)" = draft
                        printf 'overlay-restored'
                        """.formatted(relative);
                var result = executor.execute(
                        actor,
                        WORKSPACE,
                        new RepositoryExecutor.CopyRequest(copy),
                        Optional.empty(),
                        check,
                        Duration.ofSeconds(8),
                        cancellation);
                assertThat(result.exitCode()).as(result.stderr()).isZero();
                assertThat(result.stdout()).isEqualTo("overlay-restored");
                assertThat(executor.discard(actor, WORKSPACE, new RepositoryExecutor.DiscardRequest(copy), cancellation)
                                .status())
                        .isEqualTo(RepositoryExecutor.DiscardStatus.DISCARDED);
                System.out.println("RESTORED " + JSON.writeValueAsString(Map.of("copy", copy, "scope", args[2])));
            }
        }
    }
}
