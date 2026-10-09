package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.core607.poketto.games.GameBundle;
import io.github.core607.poketto.games.GameException;
import io.github.core607.poketto.games.GameRunner;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class IsolatedGameRunnerTests {
    private final JsonMapper json = JsonMapper.shared();
    private final WorkerClient client = mock(WorkerClient.class);
    private final GameRunner runner = new IsolatedGameRunner(client, json, 1);
    private final GameBundle bundle = new GameBundle(1, "export function init(){}", null, Map.of());
    private final GameRunner.Identity identity =
            new GameRunner.Identity(UUID.randomUUID(), UUID.randomUUID(), WorkspaceId.random());

    @Test
    void finiteJobCarriesObservationAndStrictlyRejectsMalformedWorkerResponses() {
        when(client.gameHello()).thenReturn(new WorkerClient.Hello(UUID.randomUUID(), 15, 5));
        when(client.request(any(), any(), eq("GAME"), any(), eq(Duration.ofSeconds(10))))
                .thenReturn(json.readTree("""
                        {"ok":true,"requestId":"17370174-174b-4821-9ac3-dbe2e4fdaffd","result":{
                          "state":{"turn":1},"observation":{"text":"Ready","actions":[{"id":"next","label":"Next"}],"done":false},
                          "presentation":null}}
                        """));
        GameRunner.Result result = runner.run(identity, bundle, new GameRunner.Request("init", null, null, 1L));
        assertThat(result.state().path("turn").intValue()).isEqualTo(1);
        assertThat(result.observation().actions()).hasSize(1);
        when(client.request(any(), any(), eq("GAME"), any(), any())).thenReturn(json.readTree("{\"ok\":true}"));
        assertThatThrownBy(() -> runner.run(identity, bundle, new GameRunner.Request("init", null, null, 1L)))
                .isInstanceOf(GameException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void stateByteLimitIsCheckedBeforeContactingTheWorker() {
        var state = json.createObjectNode().put("text", "猫".repeat(12_000));
        assertThatThrownBy(() -> runner.run(identity, bundle, new GameRunner.Request("observe", state, null, null)))
                .isInstanceOf(GameException.class)
                .hasMessageContaining("byte limit");
        verifyNoInteractions(client);
    }

    @Test
    void gameFramesRetainExplicitNullFieldsAndCannotHandshakeWithRepositoryWorkers() {
        var mapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(value -> value.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
        JsonNode data =
                mapper.valueToTree(new WorkerRequests.Game(bundle, new GameRunner.Request("init", null, null, 1L)));
        assertThat(data.path("bundle").has("presentation")).isTrue();
        assertThat(data.path("request").has("action")).isTrue();
        assertThat(data.path("request").has("state")).isTrue();
        assertThatThrownBy(() -> new IsolatedGameRunner.Handshake(
                        true, 1, 0, 1, WorkerClient.MAX_FRAME, UUID.randomUUID(), 15, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
