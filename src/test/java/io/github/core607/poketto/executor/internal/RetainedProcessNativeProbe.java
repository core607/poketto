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
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.content.PortableContentExports;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Independent JVMs share only durable fixture files; the native controller SIGKILLs each producer. */
public final class RetainedProcessNativeProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
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
    private final JsonNode config;
    private final Path root;
    private final String scenario;
    private final AuthService auth = mock(AuthService.class);
    private final AuthPrincipal actor = mock(AuthPrincipal.class);
    private final WorkspaceId workspace;
    private final AccountCopyStore records;
    private final AtomicReference<AccountCopyRecord> observed = new AtomicReference<>();
    private boolean publicationArmed;
    private boolean discardArmed;

    private RetainedProcessNativeProbe(Path configuration, String mode) throws Exception {
        config = JSON.readTree(Files.readString(configuration));
        scenario = mode.substring(mode.lastIndexOf('-') + 1);
        if (!Set.of("acknowledged", "interrupted", "uncertain", "beforepublish", "afterpublish", "discarding")
                .contains(scenario)) {
            throw new IllegalArgumentException("Unknown process-loss scenario");
        }
        root = path("publicFixture").resolve("process-" + scenario);
        boolean producer = mode.startsWith("retained-produce-");
        if (producer) {
            Files.createDirectory(
                    root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Files.writeString(
                    root.resolve("identity.json"),
                    JSON.writeValueAsString(new Identity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())));
        }
        var identity = JSON.readValue(Files.readString(root.resolve("identity.json")), Identity.class);
        workspace = new WorkspaceId(identity.workspace());
        authorize(identity);
        records = spy(AccountCopyTestData.disk(path("accountMetadata")));
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
        if (args.length != 2 || !System.getProperty("os.name").equals("Linux")) {
            throw new IllegalArgumentException("Native process recovery requires Linux and a fixture configuration");
        }
        var probe = new RetainedProcessNativeProbe(Path.of(args[0]), args[1]);
        if (args[1].startsWith("retained-produce-")) {
            probe.produce();
        } else if (args[1].startsWith("retained-resume-")) {
            probe.resume();
        } else {
            throw new IllegalArgumentException("Unknown retained process mode");
        }
    }

    private void authorize(Identity identity) {
        when(actor.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(actor.subjectId()).thenReturn(identity.subject());
        when(actor.accountId()).thenReturn(identity.account());
        var access = new WorkspaceAccess(workspace, actor, MembershipRole.OWNER, Set.of(Capability.values()));
        when(auth.authorize(any(), any())).thenReturn(access);
        when(auth.authorize(any(), any(), any(Capability[].class))).thenReturn(access);
        doAnswer(call -> ((Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
    }

    private IsolatedRepositoryExecutor adapter(PublicExecutionNativeFixture fixture) {
        return new ExecutorConfiguration()
                .isolatedRepositoryExecutor(
                        records,
                        auth,
                        fixture.exports(),
                        mock(PortableContentExports.class),
                        fixture.media(auth),
                        fixture.reader(auth),
                        fixture.patches(auth),
                        fixture.moves(auth),
                        JSON,
                        path("socket"),
                        path("privateKey"),
                        4,
                        45,
                        8);
    }

    private void produce() throws Exception {
        try (var fixture =
                        new PublicExecutionNativeFixture(root.resolve("authority"), path("exports"), auth, workspace);
                var executor = adapter(fixture)) {
            var result = execute(
                    executor,
                    "producer",
                    new RepositoryExecutor.CopyRequest("new", null, false),
                    "set -eu; printf 'acknowledged local draft' > private/draft.md; "
                            + "python3 -c \"from pathlib import Path; Path('private/draft.bin').write_bytes(bytes([0,255,9]))\"; "
                            + "printf 'saved before loss' > private/saved-before.md; poketto save private/saved-before.md");
            AccountCopyRecord before = readRecord();
            assertThat(before.phase()).isEqualTo(AccountCopyRecord.Phase.READY);
            assertThat(before.state().lastSave().value().path("ok").booleanValue())
                    .isTrue();
            if (scenario.equals("interrupted")) {
                before = interruptibleCommand(executor, before);
            }
            if (scenario.equals("uncertain")) {
                uncertainCommand(executor, fixture, before);
            }
            if (scenario.equals("beforepublish") || scenario.equals("afterpublish")) {
                publicationCommand(executor, before);
            }
            if (scenario.equals("discarding")) {
                discardArmed = true;
                executor.discard(
                        actor,
                        workspace,
                        new RepositoryExecutor.DiscardRequest(before.copyId().toString(), null),
                        CANCELLATION);
                throw new IllegalStateException("Discard escaped the process-loss barrier");
            }
            readyToKill(before);
        }
    }

    private void observeWrites(AccountCopyStore.Lease lease, AccountCopyStore.Lease original) {
        doAnswer(call -> {
                    AccountCopyRecord next = call.getArgument(0);
                    AccountCopyRecord previous = lease.record().orElse(null);
                    boolean completion = publicationArmed
                            && previous != null
                            && previous.phase() == AccountCopyRecord.Phase.RUNNING
                            && next.phase() == AccountCopyRecord.Phase.READY;
                    if (completion && scenario.equals("beforepublish")) {
                        readyToKill(previous);
                    }
                    original.write(next);
                    observed.set(next);
                    if (completion && scenario.equals("afterpublish")) {
                        readyToKill(next);
                    }
                    return null;
                })
                .when(lease)
                .write(any());
        doAnswer(call -> {
                    if (discardArmed) {
                        readyToKill(lease.record().orElseThrow());
                    }
                    original.remove(call.getArgument(0));
                    return null;
                })
                .when(lease)
                .remove(any());
    }

    private AccountCopyRecord readRecord() throws Exception {
        try (var lease = records.acquire(owner())) {
            return lease.record().orElseThrow();
        }
    }

    private void publicationCommand(IsolatedRepositoryExecutor executor, AccountCopyRecord before) {
        publicationArmed = true;
        execute(
                executor,
                "producer",
                new RepositoryExecutor.CopyRequest(before.copyId().toString(), null, false),
                "printf 'completion before response' > private/draft.md");
        throw new IllegalStateException("Command escaped the journal publication barrier");
    }

    private void uncertainCommand(
            IsolatedRepositoryExecutor executor, PublicExecutionNativeFixture fixture, AccountCopyRecord before) {
        fixture.afterSuccessfulPush(candidate -> {
            AccountCopyRecord record = observed.get();
            assertThat(record.phase()).isEqualTo(AccountCopyRecord.Phase.RUNNING);
            assertThat(record.state().uncertain()).isTrue();
            assertThat(record.state().attempt().commit()).isEqualTo(candidate);
            try {
                readyToKill(record);
            } catch (IOException | InterruptedException failure) {
                throw new IllegalStateException("Could not signal process loss after the remote push", failure);
            }
        });
        execute(
                executor,
                "producer",
                new RepositoryExecutor.CopyRequest(before.copyId().toString(), null, false),
                "set -eu; printf 'candidate before process loss' > private/uncertain.md; poketto save private/uncertain.md");
        throw new IllegalStateException("Producer escaped the remote-push termination barrier");
    }

    private void readyToKill(AccountCopyRecord before) throws IOException, InterruptedException {
        Files.writeString(root.resolve("before.json"), JSON.writeValueAsString(before));
        System.out.println(JSON.writeValueAsString(new Ready(scenario)));
        requestKill();
    }

    private AccountCopyRecord interruptibleCommand(IsolatedRepositoryExecutor executor, AccountCopyRecord before)
            throws Exception {
        var copy = new RepositoryExecutor.CopyRequest(before.copyId().toString(), null, false);
        var running = CompletableFuture.supplyAsync(
                () -> execute(
                        executor,
                        "producer",
                        copy,
                        "set -eu; printf 'host acknowledged during command' > private/inside.md; poketto save private/inside.md; sleep 60"));
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (System.nanoTime() < deadline) {
            assertThat(running.isDone())
                    .as("producer must remain in an uncompleted command")
                    .isFalse();
            AccountCopyRecord record = observed.get();
            if (record.phase() == AccountCopyRecord.Phase.RUNNING
                    && record.state().fileBaselines().containsKey("private/inside.md")) {
                assertThat(record.state()
                                .fileBaselines()
                                .get("private/inside.md")
                                .source())
                        .isEqualTo("host acknowledged during command");
                assertThat(record.state().lastSave().value().path("ok").booleanValue())
                        .isTrue();
                return record;
            }
            Thread.sleep(30);
        }
        throw new IllegalStateException("Running command did not persist its acknowledged host save");
    }

    private void requestKill() throws IOException, InterruptedException {
        Path control = path("control");
        var request = new Control("kill-application", UUID.randomUUID());
        Files.writeString(control.resolve("request.tmp"), JSON.writeValueAsString(request));
        Files.move(control.resolve("request.tmp"), control.resolve("request.json"), StandardCopyOption.ATOMIC_MOVE);
        // Returning normally would run adapter.close(); only the external SIGKILL may end this producer.
        Thread.sleep(30000);
        throw new IllegalStateException("Native controller did not kill the producer JVM");
    }

    private void resume() throws Exception {
        var before = JSON.readValue(Files.readString(root.resolve("before.json")), AccountCopyRecord.class);
        assertThat(encoded(readRecord())).isEqualTo(encoded(before));
        RetainedSaveState selected = before.state();
        try (var fixture = PublicExecutionNativeFixture.reopen(
                        root.resolve("authority"), path("exports"), auth, workspace);
                var executor = adapter(fixture)) {
            assertThat(fixture.sourceCommit())
                    .isEqualTo(
                            selected.attempt() == null
                                    ? selected.baseCommit()
                                    : selected.attempt().commit());
            if (scenario.equals("discarding")) {
                resumeDiscard(executor, fixture, before);
            } else {
                var result = execute(
                        executor,
                        "new-process-new-transport",
                        new RepositoryExecutor.CopyRequest(before.copyId().toString(), null, false),
                        inspection(before));
                AccountCopyRecord after = readRecord();
                assertRestored(before, after, selected, result);
                if (selected.uncertain()) {
                    after = reconcile(executor, fixture, after, selected);
                }
                execute(
                        executor,
                        "another-new-transport",
                        new RepositoryExecutor.CopyRequest(after.copyId().toString(), null, false),
                        "poketto save private/draft.md");
                assertThat(fixture.reader(auth)
                                .getFile(actor, workspace, Optional.empty(), "private/draft.md")
                                .source())
                        .contains(expectedDraft());
                executor.discard(
                        actor,
                        workspace,
                        new RepositoryExecutor.DiscardRequest(after.copyId().toString(), null),
                        CANCELLATION);
            }
        }
        System.out.println(JSON.writeValueAsString(new Result(
                "retained-jvm-loss-" + scenario,
                "PASS",
                "synthetic-only",
                classHash(RetainedProcessNativeProbe.class),
                classHash(IsolatedRepositoryExecutor.class))));
    }

    private void resumeDiscard(
            IsolatedRepositoryExecutor executor, PublicExecutionNativeFixture fixture, AccountCopyRecord before) {
        assertThat(before.phase()).isEqualTo(AccountCopyRecord.Phase.DISCARDING);
        var discard = new RepositoryExecutor.DiscardRequest(before.copyId().toString(), null);
        assertThat(executor.discard(actor, workspace, discard, CANCELLATION).status())
                .isEqualTo(RepositoryExecutor.DiscardStatus.DISCARDED);
        assertThat(executor.discard(actor, workspace, discard, CANCELLATION).status())
                .isEqualTo(RepositoryExecutor.DiscardStatus.ABSENT);
        var fresh = execute(
                executor,
                "new-after-discard",
                new RepositoryExecutor.CopyRequest("new", null, false),
                "test ! -e private/draft.md && test ! -e private/draft.bin");
        assertThat(fresh.copyId()).isNotEqualTo(before.copyId().toString());
        assertThat(fixture.reader(auth)
                        .getFile(actor, workspace, Optional.empty(), "private/saved-before.md")
                        .source())
                .contains("saved before loss");
        executor.discard(actor, workspace, new RepositoryExecutor.DiscardRequest(fresh.copyId(), null), CANCELLATION);
    }

    private AccountCopyRecord reconcile(
            IsolatedRepositoryExecutor executor,
            PublicExecutionNativeFixture fixture,
            AccountCopyRecord before,
            RetainedSaveState selected)
            throws Exception {
        execute(
                executor,
                "reconcile-new-process",
                new RepositoryExecutor.CopyRequest(before.copyId().toString(), null, false),
                "poketto recover");
        AccountCopyRecord after = readRecord();
        assertThat(fixture.pushes())
                .as("reconciliation must not repeat a successful remote push")
                .isZero();
        assertThat(after.state().uncertain()).isFalse();
        assertThat(after.state().attempt()).isNull();
        assertThat(after.state().baseCommit()).isEqualTo(selected.attempt().commit());
        assertThat(after.state().lastSave().value().path("ok").booleanValue()).isTrue();
        assertThat(fixture.reader(auth)
                        .getFile(actor, workspace, Optional.empty(), "private/uncertain.md")
                        .source())
                .contains("candidate before process loss");
        return after;
    }

    private String inspection(AccountCopyRecord before) {
        String command = "set -eu; test \"$(git rev-parse HEAD)\" = "
                + before.state().originalCommit()
                + "; test \"$(cat private/draft.md)\" = '" + expectedDraft() + "'; "
                + "test \"$(cat private/saved-before.md)\" = 'saved before loss'; "
                + "python3 -c \"from pathlib import Path; assert Path('private/draft.bin').read_bytes() == bytes([0,255,9])\"; ";
        if (scenario.equals("uncertain")) {
            command += "test \"$(cat private/uncertain.md)\" = 'candidate before process loss'; ";
        } else if (scenario.equals("interrupted")) {
            command += "test \"$(cat private/inside.md)\" = 'host acknowledged during command'; ";
        }
        return command + "poketto status";
    }

    private String expectedDraft() {
        return (scenario.equals("afterpublish") || scenario.equals("beforepublish"))
                ? "completion before response"
                : "acknowledged local draft";
    }

    private static void assertRestored(
            AccountCopyRecord before,
            AccountCopyRecord after,
            RetainedSaveState selected,
            RepositoryExecutor.ExecutionResult result) {
        assertThat(after.copyId()).isEqualTo(before.copyId());
        assertThat(after.revision()).isGreaterThan(before.revision());
        assertThat(after.writer().appBootId()).isNotEqualTo(before.writer().appBootId());
        assertThat(after.writer().workerBootId()).isEqualTo(before.writer().workerBootId());
        assertThat(after.original()).isEqualTo(before.original());
        assertThat(encoded(after.state())).isEqualTo(encoded(selected));
        assertThat(result.retention().resumed()).isTrue();
        assertThat(result.retention().lastInterruptedCommand()).isEqualTo(before.executionId());
        assertThat(after.phase()).isEqualTo(AccountCopyRecord.Phase.READY);
    }

    private RepositoryExecutor.ExecutionResult execute(
            IsolatedRepositoryExecutor executor,
            String transport,
            RepositoryExecutor.CopyRequest copy,
            String command) {
        var result = executor.execute(
                actor, workspace, transport, copy, Optional.empty(), command, Duration.ofSeconds(30), CANCELLATION);
        assertThat(result.exitCode())
                .as("%s %s", result.stdout(), result.stderr())
                .isZero();
        return result;
    }

    private AccountCopyRecord.Owner owner() {
        return new AccountCopyRecord.Owner(actor.accountId(), workspace.value(), true);
    }

    private static JsonNode encoded(Object value) {
        // Compare persisted values, including commit byte arrays, through their actual JSON encoding.
        return JSON.readTree(JSON.writeValueAsBytes(value));
    }

    private Path path(String name) {
        return Path.of(config.path(name).stringValue());
    }

    private static String classHash(Class<?> type) throws Exception {
        try (var bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes()));
        }
    }

    private record Identity(UUID subject, UUID account, UUID workspace) {}

    private record Control(String operation, UUID id) {}

    private record Ready(String retainedLossReady) {}

    private record Result(
            String test, String result, String source, String processProbeClassSha256, String adapterClassSha256) {}
}
