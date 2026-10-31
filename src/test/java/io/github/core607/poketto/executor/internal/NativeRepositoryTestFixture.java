package io.github.core607.poketto.executor.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

abstract class NativeRepositoryTestFixture {
    @TempDir
    Path root;

    final SandcrabClient client = mock(SandcrabClient.class);
    final AuthService auth = mock(AuthService.class);
    final AuthPrincipal principal = mock(AuthPrincipal.class);
    final RepositorySnapshotExports exports = mock(RepositorySnapshotExports.class);
    final SelectedFileSaves saves = mock(SelectedFileSaves.class);
    final ExecutionCancellation cancellation = mock(ExecutionCancellation.class);
    final WorkspaceId workspace = WorkspaceId.random();
    final UUID account = UUID.randomUUID();
    final String epoch = UUID.randomUUID().toString();
    final String commit = "a".repeat(40);
    final Map<String, SandcrabProtocol.Session> sessions = new ConcurrentHashMap<>();
    final Map<String, SandcrabProtocol.Run> runs = new ConcurrentHashMap<>();
    final Map<String, SandcrabVolume> volumes = new ConcurrentHashMap<>();
    final Map<String, SandcrabProtocol.Upload> uploads = new ConcurrentHashMap<>();
    final List<String> commands = new ArrayList<>();
    final AtomicBoolean closeFails = new AtomicBoolean();
    String stdout = "output";
    String runState = "succeeded";
    int exitCode;
    final AtomicBoolean refuseNextCommand = new AtomicBoolean();
    SocketAccountJournal journal;
    AccountCopyRecord.Owner owner;

    @BeforeEach
    void configure() throws Exception {
        root = root.toRealPath();
        journal = new SocketAccountJournal();
        owner = new AccountCopyRecord.Owner(account, workspace.value(), true);
        when(principal.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(principal.subjectId()).thenReturn(UUID.randomUUID());
        when(principal.accountId()).thenReturn(account);
        when(auth.authorize(any(), any(), any(Capability[].class)))
                .thenReturn(new WorkspaceAccess(
                        workspace, principal, MembershipRole.OWNER, EnumSet.allOf(Capability.class)));
        when(client.info())
                .thenReturn(new SandcrabProtocol.Info(
                        1,
                        epoch,
                        Set.of("runs", "sessions", "volumes", "files", "calls"),
                        Map.of(
                                "repository",
                                new SandcrabProtocol.Profile(60_000, 65536, "none", 120_000, 8_388_608, true))));
        when(exports.create(any(), any(), any()))
                .thenReturn(new RepositorySnapshotExports.Export(UUID.randomUUID(), commit, "b".repeat(64), 100));
        doAnswer(call -> {
                    Consumer<Path> consumer = call.getArgument(3);
                    consumer.accept(Path.of("synthetic.bundle"));
                    return null;
                })
                .when(exports)
                .withBundle(any(), any(), any(), any());
        nativeResponses();
    }

    void nativeResponses() {
        when(client.createVolume(any())).thenAnswer(call -> {
            SandcrabVolume.Plan plan = call.getArgument(0);
            var volume = new SandcrabVolume(
                    plan.id(),
                    plan.profile(),
                    "ready",
                    null,
                    plan.byteLimit(),
                    plan.inodeLimit(),
                    null,
                    "c".repeat(64),
                    false,
                    null);
            volumes.put(volume.id(), volume);
            return volume;
        });
        when(client.volume(anyString())).thenAnswer(call -> volumes.get(call.getArgument(0)));
        when(client.open(any())).thenAnswer(call -> {
            SandcrabProtocol.SessionPlan plan = call.getArgument(0);
            var session = new SandcrabProtocol.Session(
                    plan.id(),
                    plan.serverId(),
                    plan.profile(),
                    "ready",
                    System.currentTimeMillis() + plan.ttlMillis(),
                    null,
                    plan.volumeId(),
                    null,
                    null);
            sessions.put(session.id(), session);
            return session;
        });
        when(client.session(any())).thenAnswer(call -> {
            SandcrabProtocol.Reference reference = call.getArgument(0);
            SandcrabProtocol.Session session = sessions.get(reference.id());
            if (session == null) {
                throw new SandcrabClient.Rejected(404, "SESSION_NOT_FOUND");
            }
            return session;
        });
        when(client.close(any())).thenAnswer(call -> {
            if (closeFails.get()) {
                throw new WorkerUnavailableException();
            }
            SandcrabProtocol.Reference reference = call.getArgument(0);
            SandcrabProtocol.Session before = sessions.get(reference.id());
            var session = new SandcrabProtocol.Session(
                    before.id(),
                    before.serverId(),
                    before.profile(),
                    "closed",
                    before.expiresAtMillis(),
                    null,
                    before.volumeId(),
                    null,
                    null);
            sessions.put(session.id(), session);
            return session;
        });
        when(client.renew(any(), anyLong())).thenAnswer(call -> {
            SandcrabProtocol.Session expected = call.getArgument(0);
            SandcrabProtocol.Session current = sessions.get(expected.id());
            if (current == null) {
                throw new SandcrabClient.Rejected(404, "SESSION_NOT_FOUND");
            }
            if (current.terminal()) {
                throw new SandcrabClient.Rejected(409, "SESSION_NOT_READY");
            }
            return current;
        });
        when(client.command(any(), any())).thenAnswer(call -> {
            SandcrabProtocol.Session session = call.getArgument(0);
            SandcrabProtocol.CommandPlan command = call.getArgument(1);
            boolean userCommand = command.command().equals("printf user-command");
            if (userCommand && refuseNextCommand.compareAndSet(true, false)) {
                throw new SandcrabClient.Rejected(429, "RESULT_CAPACITY");
            }
            commands.add(command.command());
            var run = new SandcrabProtocol.Run(
                    command.id(),
                    command.serverId(),
                    session.profile(),
                    userCommand ? runState : "succeeded",
                    new SandcrabProtocol.Result(
                            userCommand ? exitCode : 0, userCommand ? stdout : "output", "", false, null),
                    session.id());
            runs.put(run.id(), run);
            return run;
        });
        when(client.get(any())).thenAnswer(call -> runs.get(((SandcrabProtocol.Reference) call.getArgument(0)).id()));
        when(client.register(any(), any())).thenAnswer(call -> upload(call.getArgument(1)));
        when(client.register(any(), any(), any())).thenAnswer(call -> upload(call.getArgument(1)));
        when(client.send(any(), any(Path.class))).thenAnswer(call -> upload(call.getArgument(0)));
        when(client.send(any(), any(Path.class), any(), any())).thenAnswer(call -> {
            ((Runnable) call.getArgument(3)).run();
            return upload(call.getArgument(0));
        });
        when(client.upload(any()))
                .thenAnswer(call -> uploads.get(((SandcrabProtocol.Reference) call.getArgument(0)).id()));
    }

    SandcrabProtocol.Upload upload(SandcrabProtocol.UploadPlan plan) {
        var uploaded = new SandcrabProtocol.Upload(
                plan.id(),
                plan.serverId(),
                plan.sessionId(),
                "repository",
                sessions.get(plan.sessionId()).volumeId(),
                plan.path(),
                plan.format(),
                plan.bytes(),
                plan.sha256(),
                "d".repeat(64),
                "succeeded",
                null,
                "committed");
        uploads.put(uploaded.id(), uploaded);
        return uploaded;
    }

    NativeRepositoryWorkspace workspaces() {
        return new NativeRepositoryWorkspace(
                client, auth, exports, saves, journal.store(), "repository", 8_388_608, 256);
    }

    NativeRepositoryExecutor executor(NativeRepositoryExecutor.CommandObserver observer) {
        return executor(observer, new ExecutionLimits(1, 1, Map.of(), Map.of()), System::nanoTime);
    }

    NativeRepositoryExecutor executor(
            NativeRepositoryExecutor.CommandObserver observer, ExecutionLimits limits, LongSupplier clock) {
        NativeRepositoryHost host = repositoryHost();
        var artifacts = new NativeArtifacts(
                new NativeTransferStore(root.resolve(UUID.randomUUID().toString()), 16L * 1024 * 1024, 64),
                Clock.systemUTC());
        return new NativeRepositoryExecutor(
                client, auth, journal.store(), workspaces(), host, artifacts, observer, limits, clock);
    }

    NativeRepositoryHost repositoryHost() {
        return new NativeRepositoryHost(
                saves,
                (lease, path, limit) -> new byte[0],
                mock(NativeArtifactCommands.class),
                mock(NativeMediaCommands.class),
                mock(NativePlanCommands.class),
                mock(NativeExportCommands.class));
    }

    RepositoryExecutor.ExecutionResult execute(NativeRepositoryExecutor executor, String copy) {
        return executor.execute(
                principal,
                workspace,
                new RepositoryExecutor.CopyRequest(copy),
                Optional.empty(),
                "printf user-command",
                Duration.ofSeconds(1),
                cancellation);
    }

    AccountCopyRecord legacyCopy() {
        try (var held = new AccountCommand(journal.store(), owner, cancellation)) {
            var writer = new AccountCopyRecord.Writer(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            held.initialize(
                    UUID.randomUUID(),
                    writer,
                    new SelectedFileSaves.State(commit).snapshot(),
                    null,
                    sink -> {},
                    AccountCopyRecord.Phase.INITIALIZING);
            held.bind(writer);
        }
        return journal.record(owner).orElseThrow();
    }
}
