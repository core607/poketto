package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
    private final RetainedCopyStore records;
    private final RetainedWorkStores stores;

    private RetainedProcessNativeProbe(Path configuration, String mode) throws Exception {
        config = JSON.readTree(Files.readString(configuration));
        scenario = mode.substring(mode.lastIndexOf('-') + 1);
        if (!Set.of("acknowledged", "interrupted", "uncertain", "beforepublish", "afterpublish")
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
        records = spy(new RetainedCopyStore(
                root.resolve("records"),
                new RetainedCopyStore.Limits(4, 8 * 1024 * 1024, 64 * 1024 * 1024, 0, Duration.ofMinutes(10)),
                Clock.systemUTC()));
        stores = RetainedBaselineTestData.stores(records, root.resolve("originals"));
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
                        Optional.of(stores),
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
            RetainedCopyRecord before = records.read(owner(), UUID.fromString(result.copyId()));
            assertThat(before.command()).isNull();
            assertThat(before.acknowledged()
                            .state()
                            .lastSave()
                            .value()
                            .path("ok")
                            .booleanValue())
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
            readyToKill(before);
        }
    }

    private void publicationCommand(IsolatedRepositoryExecutor executor, RetainedCopyRecord acknowledged) {
        doAnswer(call -> {
                    RetainedCopyRecord next = call.getArgument(2);
                    if (next.command() != null) {
                        return call.callRealMethod();
                    }
                    RetainedCopyRecord current = records.read(owner(), acknowledged.copyId());
                    assertThat(current.command()).isNotNull();
                    assertThat(current.acknowledged()).isEqualTo(acknowledged.acknowledged());
                    assertThat(next.acknowledged().id())
                            .isNotEqualTo(current.acknowledged().id());
                    if (scenario.equals("beforepublish")) {
                        readyToKill(current);
                    }
                    call.callRealMethod();
                    assertThat(encoded(records.read(owner(), next.copyId()))).isEqualTo(encoded(next));
                    readyToKill(next);
                    throw new IllegalStateException("Producer returned past the acknowledgement publication barrier");
                })
                .when(records)
                .replace(anyLong(), anyLong(), any());
        execute(
                executor,
                "producer",
                new RepositoryExecutor.CopyRequest(acknowledged.copyId().toString(), acknowledged.generation(), false),
                "printf 'completion before response' > private/draft.md");
        throw new IllegalStateException("Command response escaped the publication termination barrier");
    }

    private void uncertainCommand(
            IsolatedRepositoryExecutor executor,
            PublicExecutionNativeFixture fixture,
            RetainedCopyRecord acknowledged) {
        fixture.afterSuccessfulPush(candidate -> {
            RetainedCopyRecord record = records.read(owner(), acknowledged.copyId());
            assertThat(record.command()).isNotNull();
            RetainedSaveState state = record.command().checkpoint().state();
            assertThat(state.uncertain()).isTrue();
            assertThat(state.attempt().commit()).isEqualTo(candidate);
            assertThat(record.acknowledged()).isEqualTo(acknowledged.acknowledged());
            try {
                readyToKill(record);
            } catch (IOException | InterruptedException failure) {
                throw new IllegalStateException("Could not signal process loss after the remote push", failure);
            }
        });
        execute(
                executor,
                "producer",
                new RepositoryExecutor.CopyRequest(acknowledged.copyId().toString(), acknowledged.generation(), false),
                "set -eu; printf 'candidate before process loss' > private/uncertain.md; poketto save private/uncertain.md");
        throw new IllegalStateException("Producer returned past the remote-push termination barrier");
    }

    private void readyToKill(RetainedCopyRecord before) throws IOException, InterruptedException {
        Files.writeString(root.resolve("before.json"), JSON.writeValueAsString(before));
        System.out.println(JSON.writeValueAsString(new Ready(scenario)));
        requestKill();
    }

    private RetainedCopyRecord interruptibleCommand(
            IsolatedRepositoryExecutor executor, RetainedCopyRecord acknowledged) throws Exception {
        var copy =
                new RepositoryExecutor.CopyRequest(acknowledged.copyId().toString(), acknowledged.generation(), false);
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
            RetainedCopyRecord record;
            try {
                record = records.read(owner(), acknowledged.copyId());
            } catch (RetainedCopyException busy) {
                if (busy.reason() != RetainedCopyException.Reason.BUSY) {
                    throw busy;
                }
                Thread.sleep(30);
                continue;
            }
            if (record.command() != null) {
                RetainedSaveState state = record.command().checkpoint().state();
                if (state.fileBaselines().containsKey("private/inside.md")) {
                    assertThat(state.fileBaselines().get("private/inside.md").source())
                            .isEqualTo("host acknowledged during command");
                    assertThat(state.lastSave().value().path("ok").booleanValue())
                            .isTrue();
                    assertThat(record.acknowledged()).isEqualTo(acknowledged.acknowledged());
                    return record;
                }
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
        var before = JSON.readValue(Files.readString(root.resolve("before.json")), RetainedCopyRecord.class);
        assertThat(encoded(records.read(owner(), before.copyId()))).isEqualTo(encoded(before));
        RetainedSaveState selected = before.command() == null
                ? before.acknowledged().state()
                : before.command().checkpoint().state();
        try (var fixture = PublicExecutionNativeFixture.reopen(
                        root.resolve("authority"), path("exports"), auth, workspace);
                var executor = adapter(fixture)) {
            assertThat(fixture.sourceCommit())
                    .isEqualTo(
                            selected.attempt() == null
                                    ? selected.baseCommit()
                                    : selected.attempt().commit());
            String inspection = inspection(before);
            var result = execute(
                    executor,
                    "new-process-new-transport",
                    new RepositoryExecutor.CopyRequest(before.copyId().toString(), before.generation(), true),
                    inspection);
            RetainedCopyRecord after = records.read(owner(), before.copyId());
            assertRestored(before, after, selected, result);
            if (selected.uncertain()) {
                after = reconcile(executor, fixture, after, selected);
            }
            execute(
                    executor,
                    "another-new-transport",
                    new RepositoryExecutor.CopyRequest(after.copyId().toString(), after.generation(), true),
                    "poketto save private/draft.md");
            assertThat(fixture.reader(auth)
                            .getFile(actor, workspace, Optional.empty(), "private/draft.md")
                            .source())
                    .contains(expectedDraft());
        }
        System.out.println(JSON.writeValueAsString(new Result(
                "retained-jvm-loss-" + scenario,
                "PASS",
                "synthetic-only",
                classHash(RetainedProcessNativeProbe.class),
                classHash(IsolatedRepositoryExecutor.class))));
    }

    private RetainedCopyRecord reconcile(
            IsolatedRepositoryExecutor executor,
            PublicExecutionNativeFixture fixture,
            RetainedCopyRecord before,
            RetainedSaveState selected) {
        execute(
                executor,
                "reconcile-new-process",
                new RepositoryExecutor.CopyRequest(before.copyId().toString(), before.generation(), true),
                "poketto recover");
        RetainedCopyRecord after = records.read(owner(), before.copyId());
        assertThat(fixture.pushes())
                .as("reconciliation must not repeat an acknowledged remote push")
                .isZero();
        assertThat(after.acknowledged().state().uncertain()).isFalse();
        assertThat(after.acknowledged().state().attempt()).isNull();
        assertThat(after.acknowledged().state().baseCommit())
                .isEqualTo(selected.attempt().commit());
        assertThat(after.acknowledged().state().lastSave().value().path("ok").booleanValue())
                .isTrue();
        assertThat(fixture.reader(auth)
                        .getFile(actor, workspace, Optional.empty(), "private/uncertain.md")
                        .source())
                .contains("candidate before process loss");
        return after;
    }

    private String inspection(RetainedCopyRecord before) {
        String command = "set -eu; test \"$(git rev-parse HEAD)\" = "
                + before.acknowledged().state().originalCommit()
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
        return scenario.equals("afterpublish") ? "completion before response" : "acknowledged local draft";
    }

    private static void assertRestored(
            RetainedCopyRecord before,
            RetainedCopyRecord after,
            RetainedSaveState selected,
            RepositoryExecutor.ExecutionResult result) {
        assertThat(after.copyId()).isEqualTo(before.copyId());
        assertThat(after.generation()).isEqualTo(before.generation() + 1);
        assertThat(after.writer().appBootId()).isNotEqualTo(before.writer().appBootId());
        assertThat(after.writer().workerBootId()).isEqualTo(before.writer().workerBootId());
        assertThat(after.originalBaseline()).isEqualTo(before.originalBaseline());
        assertThat(encoded(after.acknowledged().state())).isEqualTo(encoded(selected));
        assertThat(result.retention().resumed()).isTrue();
        assertThat(result.retention().lastInterruptedCommand())
                .isEqualTo(before.command() == null ? null : before.command().id());
        assertThat(after.command()).isNull();
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

    private RetainedCopyRecord.Owner owner() {
        return new RetainedCopyRecord.Owner(actor.subjectId(), workspace.value());
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
