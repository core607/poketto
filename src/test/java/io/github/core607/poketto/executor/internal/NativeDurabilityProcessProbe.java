package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Independent JVMs use the real native runtime; only test assembly supplies crash barriers and identity. */
public final class NativeDurabilityProcessProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SCENARIOS =
            Set.of("uncertain", "beforepublish", "afterpublish", "discarding", "expired");
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
    private final Configuration config;
    private final String scenario;
    private final boolean producer;
    private final Path root;
    private final AuthService auth = mock(AuthService.class);
    private final AuthPrincipal actor = mock(AuthPrincipal.class);
    private final WorkspaceId workspace;
    private final SandcrabClient client;
    private final AccountCopyStore records;
    private final AtomicReference<AccountCopyRecord> observed = new AtomicReference<>();
    private final AtomicReference<UUID> completedCommand = new AtomicReference<>();
    private final NativeDurabilityBarriers barriers;

    private NativeDurabilityProcessProbe(Configuration config, String scenario, boolean producer) throws Exception {
        this.config = config;
        this.scenario = scenario;
        this.producer = producer;
        root = config.root().resolve(scenario);
        if (producer) {
            Files.createDirectory(root);
            Files.createDirectory(config.metadata().resolve(scenario));
            Files.createDirectory(config.exports().resolve(scenario));
            Files.createDirectory(config.transfers().resolve(scenario));
            publish(
                    root.resolve("identity.json"),
                    new Identity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        }
        var identity = JSON.readValue(Files.readAllBytes(root.resolve("identity.json")), Identity.class);
        workspace = new WorkspaceId(identity.workspace());
        authorize(identity);
        client = ExecutorConfiguration.sandcrabClient(config.socket());
        Clock clock = !producer && scenario.equals("expired")
                ? Clock.offset(Clock.systemUTC(), Duration.ofDays(8))
                : Clock.systemUTC();
        records = spy(AccountCopyTestData.disk(config.metadata().resolve(scenario), clock));
        barriers = new NativeDurabilityBarriers(scenario, record -> checkpoint(record, null));
        doAnswer(call -> {
                    var original = (AccountCopyStore.Lease) call.callRealMethod();
                    var lease = mock(AccountCopyStore.Lease.class, delegatesTo(original));
                    observeWrites(lease, original);
                    return lease;
                })
                .when(records)
                .acquire(any());
    }

    public static void main(String[] args) throws Exception {
        ProtocolValues.require(
                args.length == 3, "native durability probe", "requires configuration, scenario and mode");
        ProtocolValues.require(SCENARIOS.contains(args[1]), "native durability scenario", "must be supported");
        ProtocolValues.require(
                Set.of("produce", "resume").contains(args[2]), "native durability mode", "must be supported");
        Configuration config = JSON.readValue(Files.readAllBytes(Path.of(args[0])), Configuration.class);
        var probe = new NativeDurabilityProcessProbe(config, args[1], args[2].equals("produce"));
        if (probe.producer) {
            probe.produce();
        } else {
            probe.resume();
        }
    }

    private void authorize(Identity identity) {
        when(actor.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(actor.subjectId()).thenReturn(identity.subject());
        when(actor.accountId()).thenReturn(identity.account());
        when(actor.credentialVersion()).thenReturn(1L);
        when(auth.siteGroup(actor)).thenReturn(SiteGroup.ADMINISTRATOR);
        var access = new WorkspaceAccess(workspace, actor, MembershipRole.OWNER, Set.of(Capability.values()));
        when(auth.authorize(any(), any())).thenReturn(access);
        when(auth.authorize(any(), any(), any(Capability[].class))).thenReturn(access);
        doAnswer(call -> ((Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
    }

    private NativeRepositoryExecutor runtime(PublicExecutionNativeFixture fixture) {
        return NativeRepositoryRuntime.create(
                client,
                auth,
                records,
                fixture.exports(),
                new SelectedFileSaves(auth, fixture.reader(auth), fixture.patches(auth), fixture.moves(auth)),
                fixture.media(auth),
                fixture.packages(auth),
                ExecutionLimits.sessions(2),
                config.transfers().resolve(scenario),
                1024 * 1024,
                "repository",
                8 * 1024 * 1024,
                256);
    }

    private void observeWrites(AccountCopyStore.Lease lease, AccountCopyStore.Lease original) {
        doAnswer(call -> {
                    AccountCopyRecord next = call.getArgument(0);
                    AccountCopyRecord previous = original.record().orElse(null);
                    if (previous != null
                            && previous.phase() == AccountCopyRecord.Phase.RUNNING
                            && next.phase() == AccountCopyRecord.Phase.READY) {
                        completedCommand.set(previous.executionId());
                    }
                    barriers.write(previous, next, () -> original.write(next));
                    observed.set(next);
                    return null;
                })
                .when(lease)
                .write(any());
        doAnswer(call -> {
                    AccountCopyRecord current = original.record().orElseThrow();
                    SandcrabVolume deleted =
                            client.volume(current.writer().storage().volumeId().toString());
                    assertThat(deleted.state()).isEqualTo("deleted");
                    publish(
                            root.resolve("deleted-before-remove-" + current.copyId() + "-" + producer + ".json"),
                            deleted);
                    barriers.remove(current, () -> original.remove(call.getArgument(0)));
                    return null;
                })
                .when(lease)
                .remove(any());
    }

    private void produce() throws Exception {
        try (var fixture = new PublicExecutionNativeFixture(
                        root.resolve("authority"), config.exports().resolve(scenario), auth, workspace);
                var executor = runtime(fixture)) {
            execute(
                    executor,
                    "new",
                    "set -eu; printf 'unsaved local draft' > private/draft.md; "
                            + "python3 -c \"from pathlib import Path; Path('private/draft.bin').write_bytes(bytes([0,255,9]))\"; "
                            + "printf 'saved before loss' > private/saved-before.md; poketto save private/saved-before.md");
            AccountCopyRecord before = record();
            assertThat(before.phase()).isEqualTo(AccountCopyRecord.Phase.READY);
            assertThat(before.state().lastSave().value().path("ok").booleanValue())
                    .isTrue();
            if (scenario.equals("uncertain")) {
                uncertain(executor, fixture, before);
            } else if (scenario.equals("discarding")) {
                barriers.arm();
                executor.discard(
                        actor,
                        workspace,
                        new RepositoryExecutor.DiscardRequest(before.copyId().toString()),
                        CANCELLATION);
            } else if (scenario.equals("expired")) {
                checkpoint(before, null);
            } else {
                barriers.arm();
                execute(
                        executor,
                        before.copyId().toString(),
                        "printf once >> private/publication-count; printf 'completion before response' > private/draft.md");
            }
            throw new IllegalStateException("Producer escaped its external process-loss barrier");
        }
    }

    private void uncertain(
            NativeRepositoryExecutor executor, PublicExecutionNativeFixture fixture, AccountCopyRecord before) {
        fixture.afterSuccessfulPush(candidate -> {
            AccountCopyRecord current = observed.get();
            assertThat(current.phase()).isEqualTo(AccountCopyRecord.Phase.RUNNING);
            assertThat(current.state().uncertain()).isTrue();
            assertThat(current.state().attempt().commit()).isEqualTo(candidate);
            checkpoint(current, candidate);
        });
        execute(
                executor,
                before.copyId().toString(),
                "set -eu; printf once >> private/uncertain-count; "
                        + "printf 'candidate before process loss' > private/uncertain.md; poketto save private/uncertain.md");
    }

    private void checkpoint(AccountCopyRecord before, String candidate) {
        try {
            publish(root.resolve("before.json"), before);
            publish(
                    root.resolve("ready.json"),
                    new Barrier(
                            scenario,
                            ProcessHandle.current().pid(),
                            before.copyId(),
                            before.writer(),
                            before.phase(),
                            before.executionId(),
                            before.executionId() == null ? completedCommand.get() : before.executionId(),
                            candidate,
                            classHash(NativeDurabilityProcessProbe.class),
                            classHash(NativeRepositoryExecutor.class)));
            Thread.sleep(90000);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not publish the process-loss checkpoint", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Process-loss checkpoint was interrupted instead of externally killed", interrupted);
        }
        throw new IllegalStateException("Host did not terminate the checkpointed producer JVM");
    }

    private void resume() throws Exception {
        var before = JSON.readValue(Files.readAllBytes(root.resolve("before.json")), AccountCopyRecord.class);
        assertThat(encoded(record())).isEqualTo(encoded(before));
        try (var fixture = PublicExecutionNativeFixture.reopen(
                        root.resolve("authority"), config.exports().resolve(scenario), auth, workspace);
                var executor = runtime(fixture)) {
            if (scenario.equals("expired") || scenario.equals("discarding")) {
                dispose(executor, fixture, before);
            } else {
                restore(executor, fixture, before);
            }
            assertThat(fixture.pushes())
                    .as("resume must never repeat a successful remote push")
                    .isZero();
        }
        publish(
                root.resolve("result.json"),
                new Result(
                        scenario,
                        "PASS",
                        classHash(NativeDurabilityProcessProbe.class),
                        classHash(NativeRepositoryExecutor.class),
                        "synthetic-authority-and-grant; real-JGit-XFS-native-runtime"));
    }

    private void restore(
            NativeRepositoryExecutor executor, PublicExecutionNativeFixture fixture, AccountCopyRecord before)
            throws Exception {
        String draft = scenario.equals("uncertain") ? "unsaved local draft" : "completion before response";
        String count = scenario.equals("uncertain") ? "uncertain-count" : "publication-count";
        var result = execute(
                executor,
                before.copyId().toString(),
                "set -eu; test \"$(cat private/draft.md)\" = '" + draft
                        + "'; test \"$(cat private/" + count + ")\" = once; "
                        + "python3 -c \"from pathlib import Path; assert Path('private/draft.bin').read_bytes() == bytes([0,255,9])\"; poketto status");
        AccountCopyRecord after = record();
        assertThat(after.copyId()).isEqualTo(before.copyId());
        assertThat(after.writer().appBootId()).isNotEqualTo(before.writer().appBootId());
        assertThat(after.writer().workerBootId()).isEqualTo(before.writer().workerBootId());
        assertThat(after.original()).isEqualTo(before.original());
        assertThat(encoded(after.state())).isEqualTo(encoded(before.state()));
        assertThat(result.commit()).isEqualTo(before.state().originalCommit());
        assertThat(result.retention().resumed()).isTrue();
        assertThat(after.lastInterruptedCommand()).isEqualTo(before.executionId());
        assertThat(after.phase()).isEqualTo(AccountCopyRecord.Phase.READY);
        publish(root.resolve("restored.json"), after);
        if (scenario.equals("uncertain")) {
            execute(executor, after.copyId().toString(), "poketto recover");
            AccountCopyRecord recovered = record();
            assertThat(recovered.state().uncertain()).isFalse();
            assertThat(recovered.state().attempt()).isNull();
            assertThat(recovered.state().baseCommit())
                    .isEqualTo(before.state().attempt().commit());
            assertThat(recovered.state().lastSave().value().path("ok").booleanValue())
                    .isTrue();
            assertThat(fixture.reader(auth)
                            .getFile(actor, workspace, Optional.empty(), "private/uncertain.md")
                            .source())
                    .contains("candidate before process loss");
            publish(root.resolve("recovered.json"), recovered);
        }
        discard(executor, before.copyId());
    }

    private void dispose(
            NativeRepositoryExecutor executor, PublicExecutionNativeFixture fixture, AccountCopyRecord before)
            throws Exception {
        if (scenario.equals("expired")) {
            assertThat(records.expired(before)).isTrue();
            try (var busy = records.acquire(owner())) {
                assertThat(executor.collectExpiredCopies()).isZero();
                assertThat(encoded(busy.record().orElseThrow())).isEqualTo(encoded(before));
                assertThat(client.volume(before.copyId().toString()).state()).isEqualTo("ready");
                publish(root.resolve("locked-expiry.json"), before);
            }
            var collected = new AtomicInteger();
            try (var maintenance = new AccountCopyMaintenance(
                    () -> collected.addAndGet(executor.collectExpiredCopies()), Duration.ofMillis(50))) {
                long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
                while (collected.get() == 0 && System.nanoTime() < deadline) {
                    Thread.sleep(25);
                }
                assertThat(collected.get()).isEqualTo(1);
            }
        } else {
            assertThat(before.phase()).isEqualTo(AccountCopyRecord.Phase.DISCARDING);
            assertThat(client.volume(before.copyId().toString()).state()).isEqualTo("deleted");
            discard(executor, before.copyId());
        }
        try (var held = records.acquire(owner())) {
            assertThat(held.record()).isEmpty();
        }
        requireForgotten(before.copyId());
        var fresh = execute(executor, "new", "test ! -e private/draft.md && test ! -e private/draft.bin");
        assertThat(fresh.copyId()).isNotEqualTo(before.copyId().toString());
        assertThat(fixture.reader(auth)
                        .getFile(actor, workspace, Optional.empty(), "private/saved-before.md")
                        .source())
                .contains("saved before loss");
        discard(executor, UUID.fromString(fresh.copyId()));
    }

    private void discard(NativeRepositoryExecutor executor, UUID copy) {
        var request = new RepositoryExecutor.DiscardRequest(copy.toString());
        assertThat(executor.discard(actor, workspace, request, CANCELLATION).status())
                .isEqualTo(RepositoryExecutor.DiscardStatus.DISCARDED);
        assertThat(executor.discard(actor, workspace, request, CANCELLATION).status())
                .isEqualTo(RepositoryExecutor.DiscardStatus.ABSENT);
        requireForgotten(copy);
    }

    private void requireForgotten(UUID copy) {
        try {
            client.volume(copy.toString());
            throw new IllegalStateException("Deleted native volume receipt remains after fixture disposal");
        } catch (SandcrabClient.Rejected missing) {
            assertThat(missing.code()).isEqualTo("VOLUME_NOT_FOUND");
        }
    }

    private RepositoryExecutor.ExecutionResult execute(NativeRepositoryExecutor executor, String copy, String command) {
        var result = executor.execute(
                actor,
                workspace,
                new RepositoryExecutor.CopyRequest(copy),
                Optional.empty(),
                command,
                Duration.ofSeconds(45),
                CANCELLATION);
        assertThat(result.exitCode())
                .as("%s %s", result.stdout(), result.stderr())
                .isZero();
        return result;
    }

    private AccountCopyRecord record() throws IOException {
        try (var held = records.acquire(owner())) {
            return held.record().orElseThrow();
        }
    }

    private AccountCopyRecord.Owner owner() {
        return new AccountCopyRecord.Owner(actor.accountId(), workspace.value(), true);
    }

    private static JsonNode encoded(Object value) {
        return JSON.readTree(JSON.writeValueAsBytes(value));
    }

    private static String classHash(Class<?> type) throws IOException {
        try (var bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            return HexFormat.of().formatHex(SandcrabProtocol.digest().digest(bytes.readAllBytes()));
        }
    }

    private static void publish(Path path, Object value) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".pending");
        try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var data = ByteBuffer.wrap(JSON.writeValueAsBytes(value));
            while (data.hasRemaining()) {
                channel.write(data);
            }
            channel.force(true);
        }
        assertThat(path).doesNotExist();
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
        RetainedDirectory.sync(path.getParent());
    }

    record Configuration(Path socket, Path root, Path metadata, Path exports, Path transfers) {
        Configuration {
            for (Path path : new Path[] {socket, root, metadata, exports, transfers}) {
                ProtocolValues.require(path != null && path.isAbsolute(), "native fixture path", "must be absolute");
            }
        }
    }

    record Identity(UUID subject, UUID account, UUID workspace) {}

    record Barrier(
            String scenario,
            long pid,
            UUID copyId,
            AccountCopyRecord.Writer writer,
            AccountCopyRecord.Phase phase,
            UUID executionId,
            UUID commandId,
            String candidateCommit,
            String probeClassSha256,
            String executorClassSha256) {}

    record Result(String scenario, String result, String probeClassSha256, String executorClassSha256, String scope) {}
}
