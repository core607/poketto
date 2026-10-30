package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthRevocation;
import io.github.core607.poketto.games.GameBundle;
import io.github.core607.poketto.games.GameException;
import io.github.core607.poketto.games.GameRunner;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class IsolatedGameRunnerTests {
    private static final String STEP =
            "{\"state\":{\"turn\":1},\"observation\":{\"text\":\"Ready\",\"actions\":[],\"done\":false},\"presentation\":null}";
    private final JsonMapper json = JsonMapper.shared();
    private final SandcrabClient client = mock(SandcrabClient.class);
    private final GameBundle bundle = new GameBundle(1, "export function init(){}", null, Map.of());
    private final GameRunner.Player identity = GameTestIdentities.player(WorkspaceId.random());

    @TempDir
    Path root;

    private IsolatedGameRunner runner(Consumer<GameRunner.Identity> authority) {
        return new IsolatedGameRunner(
                client, json, 1, root.resolve("owner.lock"), "/game runtime/entry.mjs", authority);
    }

    private GameRunner.Result initialize(IsolatedGameRunner runner) {
        return runner.run(identity, bundle, new GameRunner.Request("init", null, null, 1L));
    }

    @Test
    void gameInputUsesFilesAndOnlyACompleteStepIsReturned() {
        nativeService(STEP);
        try (var runner = runner(owner -> {})) {
            assertThat(initialize(runner).state().path("turn").intValue()).isEqualTo(1);
            var bytes = ArgumentCaptor.forClass(byte[].class);
            verify(client).send(any(), bytes.capture());
            String input = new String(bytes.getValue(), StandardCharsets.UTF_8);
            assertThat(input).contains("\"bundle\"", "\"request\"", "\"state\":null", "\"presentation\":null");
            assertThat(input)
                    .doesNotContain(
                            identity.principal().accountId().toString(),
                            identity.principal().subjectId().toString());
            var command = ArgumentCaptor.forClass(SandcrabProtocol.CommandPlan.class);
            verify(client).command(any(), command.capture());
            assertThat(command.getValue().command())
                    .isEqualTo("exec node --max-old-space-size=64 '/game runtime/entry.mjs' input.json");
            assertThat(command.getValue().outputLimitBytes()).isEqualTo(GamePayload.OUTPUT_BYTES);
            verify(client).forget(any(SandcrabProtocol.Session.class));
            verify(client).forget(any(SandcrabProtocol.Upload.class));
            verify(client).forget(any(SandcrabProtocol.Run.class));
        }
    }

    @Test
    void stateByteLimitIsCheckedBeforeContactingTheService() {
        try (var runner = runner(owner -> {})) {
            var state = json.createObjectNode().put("text", "猫".repeat(12_000));
            assertThatThrownBy(() -> runner.run(identity, bundle, new GameRunner.Request("observe", state, null, null)))
                    .isInstanceOf(GameException.class)
                    .hasMessageContaining("byte limit");
            verifyNoInteractions(client);
        }
    }

    @Test
    void outputCannotInventAnImageOrSmuggleAnotherJsonValue() {
        var payload = new GamePayload(json);
        for (String output : List.of(
                STEP + " {}",
                STEP.replace("\"turn\":1", "\"turn\":1,\"turn\":2"),
                STEP.replace(
                        "\"presentation\":null",
                        "\"presentation\":{\"heading\":\"x\",\"paragraphs\":[],\"image\":\"missing\"}"))) {
            assertThatThrownBy(() -> payload.result(bundle, output)).isInstanceOf(GameException.class);
        }
    }

    @Test
    void revocationDuringTheFreshAuthorityCheckFindsTheRegisteredJob() {
        nativeService(STEP);
        var reference = new AtomicReference<IsolatedGameRunner>();
        try (var runner = runner(owner -> reference
                .get()
                .revoked(new AuthRevocation(
                        identity.workspaceId(), Set.of(identity.principal().accountId()), Set.of())))) {
            reference.set(runner);
            assertThatThrownBy(() -> initialize(runner))
                    .isInstanceOf(GameException.class)
                    .hasMessageContaining("cancelled");
            verify(client, never()).open(any());
        }
    }

    @Test
    void lostAdmissionHoldsCapacityUntilTheServiceEpochChanges() {
        var epoch = new AtomicReference<>(UUID.randomUUID().toString());
        when(client.info()).thenAnswer(call -> info(epoch.get()));
        when(client.open(any())).thenThrow(new WorkerUnavailableException());
        when(client.session(any())).thenThrow(new WorkerUnavailableException());
        when(client.runs(any(), any()))
                .thenAnswer(call -> new SandcrabProtocol.IdentityPage(epoch.get(), List.of(), null));
        when(client.sessions(any(), any()))
                .thenAnswer(call -> new SandcrabProtocol.IdentityPage(epoch.get(), List.of(), null));
        when(client.uploads(any())).thenReturn(new SandcrabProtocol.UploadPage(List.of(), null));
        try (var runner = runner(owner -> {})) {
            assertThatThrownBy(() -> initialize(runner)).isInstanceOf(GameException.class);
            assertThatThrownBy(() -> initialize(runner))
                    .isInstanceOfSatisfying(
                            GameException.class,
                            error -> assertThat(error.code()).isEqualTo("GAME_BUSY"));
            epoch.set(UUID.randomUUID().toString());
            runner.collect();
            doThrow(new WorkerUnavailableException(new IOException("offline"), false))
                    .when(client)
                    .open(any());
            assertThatThrownBy(() -> initialize(runner))
                    .isInstanceOfSatisfying(
                            GameException.class,
                            error -> assertThat(error.code()).isEqualTo("GAME_UNAVAILABLE"));
        }
    }

    @Test
    void aSecondApplicationCannotSweepTheSameGameService() {
        nativeService(STEP);
        try (var first = runner(owner -> {})) {
            assertThatThrownBy(() -> runner(owner -> {}))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exclusively own");
            assertThat(initialize(first).state().path("turn").intValue()).isEqualTo(1);
        }
    }

    static SandcrabProtocol.Info info(String epoch) {
        return new SandcrabProtocol.Info(
                1,
                epoch,
                Set.of("runs", "sessions", "files"),
                Map.of(
                        "games",
                        new SandcrabProtocol.Profile(
                                5000, GamePayload.OUTPUT_BYTES, "none", 15_000, 512 * 1024, false)));
    }

    private void nativeService(String output) {
        String epoch = UUID.randomUUID().toString();
        var session = new AtomicReference<SandcrabProtocol.Session>();
        var upload = new AtomicReference<SandcrabProtocol.Upload>();
        var run = new AtomicReference<SandcrabProtocol.Run>();
        when(client.info()).thenReturn(info(epoch));
        when(client.open(any())).thenAnswer(call -> {
            SandcrabProtocol.SessionPlan plan = call.getArgument(0);
            session.set(session(plan.id(), epoch, "ready"));
            return session.get();
        });
        when(client.session(any())).thenAnswer(call -> required(session.get(), "SESSION_NOT_FOUND"));
        when(client.close(any())).thenAnswer(call -> {
            session.set(session(session.get().id(), epoch, "closed"));
            return session.get();
        });
        when(client.register(any(), any())).thenAnswer(call -> {
            SandcrabProtocol.UploadPlan plan = call.getArgument(1);
            upload.set(new SandcrabProtocol.Upload(
                    plan.id(),
                    epoch,
                    plan.sessionId(),
                    "games",
                    null,
                    plan.path(),
                    plan.format(),
                    plan.bytes(),
                    plan.sha256(),
                    "a".repeat(64),
                    "succeeded",
                    null,
                    "committed"));
            return upload.get();
        });
        when(client.send(any(), any())).thenAnswer(call -> upload.get());
        when(client.upload(any())).thenAnswer(call -> required(upload.get(), "UPLOAD_NOT_FOUND"));
        when(client.command(any(), any())).thenAnswer(call -> {
            SandcrabProtocol.CommandPlan plan = call.getArgument(1);
            run.set(new SandcrabProtocol.Run(
                    plan.id(),
                    epoch,
                    "games",
                    "succeeded",
                    new SandcrabProtocol.Result(0, output, "", false, null),
                    session.get().id()));
            session.set(session(session.get().id(), epoch, "closed"));
            return run.get();
        });
        when(client.get(any())).thenAnswer(call -> required(run.get(), "RUN_NOT_FOUND"));
        when(client.runs(any(), any())).thenReturn(new SandcrabProtocol.IdentityPage(epoch, List.of(), null));
        when(client.sessions(any(), any())).thenReturn(new SandcrabProtocol.IdentityPage(epoch, List.of(), null));
        when(client.uploads(any())).thenReturn(new SandcrabProtocol.UploadPage(List.of(), null));
    }

    private static <T> T required(T value, String missing) {
        if (value == null) {
            throw new SandcrabClient.Rejected(404, missing);
        }
        return value;
    }

    static SandcrabProtocol.Session session(String id, String epoch, String state) {
        return new SandcrabProtocol.Session(id, epoch, "games", state, 1900000000000L, null, null, null, null);
    }
}
