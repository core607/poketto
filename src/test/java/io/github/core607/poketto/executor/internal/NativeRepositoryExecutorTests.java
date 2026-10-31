package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.ExecutionUnconfirmedException;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeRepositoryExecutorTests {
    @TempDir
    Path root;

    private final SandcrabClient client = mock(SandcrabClient.class);
    private final AuthService auth = mock(AuthService.class);
    private final AuthPrincipal principal = mock(AuthPrincipal.class);
    private final RepositorySnapshotExports exports = mock(RepositorySnapshotExports.class);
    private final SelectedFileSaves saves = mock(SelectedFileSaves.class);
    private final ExecutionCancellation cancellation = mock(ExecutionCancellation.class);
    private final WorkspaceId workspace = WorkspaceId.random();
    private final UUID account = UUID.randomUUID();
    private final String epoch = UUID.randomUUID().toString();
    private final String commit = "a".repeat(40);
    private final Map<String, SandcrabProtocol.Session> sessions = new HashMap<>();
    private final Map<String, SandcrabProtocol.Run> runs = new HashMap<>();
    private final Map<String, SandcrabVolume> volumes = new HashMap<>();
    private final Map<String, SandcrabProtocol.Upload> uploads = new HashMap<>();
    private final List<String> commands = new ArrayList<>();
    private final AtomicBoolean closeFails = new AtomicBoolean();
    private SocketAccountJournal journal;
    private AccountCopyRecord.Owner owner;

    @BeforeEach
    void configure() throws Exception {
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

    private void nativeResponses() {
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
        when(client.command(any(), any())).thenAnswer(call -> {
            SandcrabProtocol.Session session = call.getArgument(0);
            SandcrabProtocol.CommandPlan command = call.getArgument(1);
            commands.add(command.command());
            var run = new SandcrabProtocol.Run(
                    command.id(),
                    command.serverId(),
                    session.profile(),
                    "succeeded",
                    new SandcrabProtocol.Result(0, "output", "", false, null),
                    session.id());
            runs.put(run.id(), run);
            return run;
        });
        when(client.get(any())).thenAnswer(call -> runs.get(((SandcrabProtocol.Reference) call.getArgument(0)).id()));
        when(client.register(any(), any())).thenAnswer(call -> upload(call.getArgument(1)));
        when(client.send(any(), any(Path.class))).thenAnswer(call -> upload(call.getArgument(0)));
        when(client.upload(any()))
                .thenAnswer(call -> uploads.get(((SandcrabProtocol.Reference) call.getArgument(0)).id()));
    }

    private SandcrabProtocol.Upload upload(SandcrabProtocol.UploadPlan plan) {
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

    private NativeRepositoryWorkspace workspaces() {
        return new NativeRepositoryWorkspace(
                client, auth, exports, saves, journal.store(), "repository", 8_388_608, 256);
    }

    private NativeRepositoryExecutor executor(NativeRepositoryExecutor.CommandObserver observer) {
        var host = new NativeRepositoryHost(saves, (lease, path, limit) -> new byte[0]);
        return new NativeRepositoryExecutor(
                client,
                auth,
                journal.store(),
                workspaces(),
                host,
                observer,
                new ExecutionLimits(1, 1, Map.of(), Map.of()));
    }

    private RepositoryExecutor.ExecutionResult execute(NativeRepositoryExecutor executor, String copy) {
        return executor.execute(
                principal,
                workspace,
                new RepositoryExecutor.CopyRequest(copy),
                Optional.empty(),
                "printf user-command",
                Duration.ofSeconds(1),
                cancellation);
    }

    @Test
    void nativeApplicationPathJournalsBeforeCommandAndPreservesTheVolumeAfterClosing() {
        try (var executor = executor((run, timeout, handler) -> {
            AccountCopyRecord running = journal.record(owner).orElseThrow();
            assertThat(running.phase()).isEqualTo(AccountCopyRecord.Phase.RUNNING);
            assertThat(running.executionId().toString()).isEqualTo(run.id());
        })) {
            RepositoryExecutor.ExecutionResult result = execute(executor, "new");
            AccountCopyRecord retained = journal.record(owner).orElseThrow();
            assertThat(retained.phase()).isEqualTo(AccountCopyRecord.Phase.READY);
            assertThat(retained.writer().storage().volumeId().toString()).isEqualTo(result.copyId());
            assertThat(retained.original()).isNotNull();
            assertThat(commands).anyMatch(source -> source.startsWith("git clone"));
            assertThat(commands.getLast()).isEqualTo("printf user-command");
            assertThat(sessions.values()).allMatch(session -> session.state().equals("closed"));
            verify(client, never()).deleteVolume(any());
        }
    }

    @Test
    void reconnectAttachesTheSameVolumeWithoutReexportingOrReplacingItsOriginals() {
        try (var executor = executor((run, timeout, handler) -> {})) {
            String copy = execute(executor, "new").copyId();
            AccountCopyRecord before = journal.record(owner).orElseThrow();
            execute(executor, copy);
            AccountCopyRecord after = journal.record(owner).orElseThrow();
            assertThat(after.writer().storage()).isEqualTo(before.writer().storage());
            assertThat(after.original()).isEqualTo(before.original());
            assertThat(after.state()).isEqualTo(before.state());
            verify(exports).create(any(), any(), any());
            verify(client).createVolume(any());
            assertThat(commands).contains("cd /workspace/repository");
        }
    }

    @Test
    void lostObservationRetainsInterruptedCommandAndNeverReexecutesIt() {
        UUID interrupted;
        String copy;
        try (var executor = executor((run, timeout, handler) -> {
            throw new WorkerUnavailableException();
        })) {
            assertThatThrownBy(() -> execute(executor, "new")).isInstanceOf(ExecutionUnconfirmedException.class);
            AccountCopyRecord retained = journal.record(owner).orElseThrow();
            assertThat(retained.phase()).isEqualTo(AccountCopyRecord.Phase.RUNNING);
            interrupted = retained.executionId();
            copy = retained.copyId().toString();
        }
        try (var resumed = executor((run, timeout, handler) -> {})) {
            assertThat(execute(resumed, copy).retention().lastInterruptedCommand())
                    .isEqualTo(interrupted);
            assertThat(commands.stream().filter("printf user-command"::equals)).hasSize(2);
            verify(client, never()).deleteVolume(any());
        }
    }

    @Test
    void unconfirmedContainmentRetainsCapacityAndReconciliationReleasesIt() {
        closeFails.set(true);
        try (var executor = executor((run, timeout, handler) -> {})) {
            assertThatThrownBy(() -> execute(executor, "new")).isInstanceOf(ExecutionUnconfirmedException.class);
            String copy = journal.record(owner).orElseThrow().copyId().toString();
            assertThatThrownBy(() -> execute(executor, copy)).isInstanceOf(ExecutionAdmissionException.class);
            closeFails.set(false);
            assertThat(execute(executor, copy).stdout()).isEqualTo("output");
            verify(client, never()).deleteVolume(any());
        }
    }

    @Test
    void legacyCopiesRemainUntouchedUntilAnExplicitImportPublishesTheirNativeBinding() {
        AccountCopyRecord before = legacyCopy();
        try (var executor = executor((run, timeout, handler) -> {})) {
            assertThatThrownBy(() -> execute(executor, before.copyId().toString()))
                    .isInstanceOf(ExecutionAdmissionException.class);
            assertThat(journal.record(owner)).contains(before);
            verify(client, never()).createVolume(any());
            verify(client, never()).open(any());
        }
    }

    private AccountCopyRecord legacyCopy() {
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

    @Test
    void verifiedImportSwitchesOnlyTheWriterAndRetainsSourceAndEverySaveBaseline() throws Exception {
        AccountCopyRecord before = legacyCopy();
        Path archive = Files.write(root.resolve("kept-source.tar"), new byte[1024]);
        var migration = new NativeRepositoryMigration(client, workspaces());
        var plan = NativeRepositoryMigration.plan(
                before,
                client.info(),
                new NativeRepositoryMigration.Archive(1024, "f".repeat(64)),
                "repository",
                8_388_608,
                256);
        var fences = new AtomicInteger();
        try (var held = new AccountCommand(journal.store(), owner, cancellation)) {
            migration.importCopy(principal, held, plan, archive, () -> {
                assertThat(held.record()).isEqualTo(before);
                fences.incrementAndGet();
            });
            AccountCopyRecord after = held.record();
            assertThat(after.copyId()).isEqualTo(before.copyId());
            assertThat(after.owner()).isEqualTo(before.owner());
            assertThat(after.original()).isEqualTo(before.original());
            assertThat(after.state()).isEqualTo(before.state());
            assertThat(after.expiresAt()).isEqualTo(before.expiresAt());
            assertThat(after.writer()).isEqualTo(plan.writer());
            migration.importCopy(principal, held, plan, archive, () -> {
                throw new AssertionError("already imported");
            });
        }
        assertThat(Files.readAllBytes(archive)).hasSize(1024);
        assertThat(fences.get()).isEqualTo(2);
        verify(client).send(any(), any(Path.class));
        verify(client, never()).deleteVolume(any());
    }

    @Test
    void lostImportReplyReconcilesTheSameReceiptWithoutReplacingEitherCopy() throws Exception {
        AccountCopyRecord before = legacyCopy();
        Path archive = Files.write(root.resolve("kept-source.tar"), new byte[1024]);
        var migration = new NativeRepositoryMigration(client, workspaces());
        var plan = NativeRepositoryMigration.plan(
                before,
                client.info(),
                new NativeRepositoryMigration.Archive(1024, "f".repeat(64)),
                "repository",
                8_388_608,
                256);
        when(client.send(any(), any(Path.class))).thenAnswer(call -> {
            upload(call.getArgument(0));
            throw new WorkerUnavailableException();
        });
        try (var held = new AccountCommand(journal.store(), owner, cancellation)) {
            assertThatThrownBy(() -> migration.importCopy(principal, held, plan, archive, () -> {}))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThat(held.record()).isEqualTo(before);
            migration.importCopy(principal, held, plan, archive, () -> {});
            assertThat(held.record().writer()).isEqualTo(plan.writer());
            assertThat(held.record().state()).isEqualTo(before.state());
        }
        verify(client).send(any(), any(Path.class));
        assertThat(Files.readAllBytes(archive)).hasSize(1024);
    }

    @Test
    void unverifiedLegacyContainmentCannotStartAnImportOrChangeItsJournal() throws Exception {
        AccountCopyRecord before = legacyCopy();
        var migration = new NativeRepositoryMigration(client, workspaces());
        var plan = NativeRepositoryMigration.plan(
                before,
                client.info(),
                new NativeRepositoryMigration.Archive(1024, "f".repeat(64)),
                "repository",
                8_388_608,
                256);
        try (var held = new AccountCommand(journal.store(), owner, cancellation)) {
            assertThatThrownBy(() -> migration.importCopy(principal, held, plan, root.resolve("unread.tar"), () -> {
                        throw new WorkerUnavailableException();
                    }))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThat(held.record()).isEqualTo(before);
        }
        verify(client, never()).createVolume(any());
        verify(client, never()).open(any());
    }

    @Test
    void revokedGrantCannotOpenOrDeleteItsWorkingCopy() {
        when(auth.authorize(any(), any(), any(Capability[].class)))
                .thenThrow(new AuthException(AuthException.Code.DENIED));
        try (var executor = executor((run, timeout, handler) -> {})) {
            assertThatThrownBy(() -> execute(executor, "new")).isInstanceOf(AuthException.class);
            verify(client, never()).open(any());
            verify(client, never()).createVolume(any());
        }
    }

    @Test
    void namedDiscardCannotRemoveTheAccountsDifferentDefaultCopy() {
        try (var executor = executor((run, timeout, handler) -> {})) {
            execute(executor, "new");
            AccountCopyRecord before = journal.record(owner).orElseThrow();
            var result = executor.discard(
                    principal,
                    workspace,
                    new RepositoryExecutor.DiscardRequest(UUID.randomUUID().toString()),
                    cancellation);
            assertThat(result.status()).isEqualTo(RepositoryExecutor.DiscardStatus.ABSENT);
            assertThat(journal.record(owner)).contains(before);
            verify(client, never()).deleteVolume(any());
        }
    }

    @Test
    void aRestrictedGrantCannotAttachTheAccountsExistingPrivateCopy() {
        try (var executor = executor((run, timeout, handler) -> {})) {
            String copy = execute(executor, "new").copyId();
            AccountCopyRecord before = journal.record(owner).orElseThrow();
            when(auth.authorize(any(), any(), any(Capability[].class)))
                    .thenReturn(new WorkspaceAccess(
                            workspace, principal, MembershipRole.MEMBER, Set.of(Capability.EXECUTE_REPOSITORY)));
            assertThatThrownBy(() -> execute(executor, copy)).isInstanceOf(ExecutionAdmissionException.class);
            assertThat(journal.record(owner)).contains(before);
            verify(client).open(any());
        }
    }

    @Test
    void cancellationBeforeAdmissionCannotCreateASandboxOrARepositoryExport() {
        when(cancellation.isCancelled()).thenReturn(true);
        try (var executor = executor((run, timeout, handler) -> {})) {
            assertThatThrownBy(() -> execute(executor, "new")).isInstanceOf(ExecutionAdmissionException.class);
            verify(client, never()).open(any());
            verify(exports, never()).create(any(), any(), any());
            assertThat(journal.record(owner)).isEmpty();
        }
    }

    @Test
    void nativeStorageBindingRoundTripsWithoutErasingLegacyJournals() {
        var legacy = new AccountCopyRecord.Writer(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String encoded = new String(SandcrabProtocol.write(legacy), StandardCharsets.UTF_8);
        String prior = encoded.replace(",\"storage\":null", "");
        assertThat(SandcrabProtocol.read(prior.getBytes(StandardCharsets.UTF_8), AccountCopyRecord.Writer.class))
                .isEqualTo(legacy);
        try (var executor = executor((run, timeout, handler) -> {})) {
            execute(executor, "new");
            AccountCopyRecord.Writer nativeWriter =
                    journal.record(owner).orElseThrow().writer();
            assertThat(SandcrabProtocol.read(SandcrabProtocol.write(nativeWriter), AccountCopyRecord.Writer.class))
                    .isEqualTo(nativeWriter);
        }
    }
}
