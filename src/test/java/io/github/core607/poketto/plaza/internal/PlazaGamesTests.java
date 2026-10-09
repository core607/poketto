package io.github.core607.poketto.plaza.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.games.GameRunner;
import io.github.core607.poketto.games.GameSaves;
import io.github.core607.poketto.plaza.PlazaCommand;
import io.github.core607.poketto.plaza.PlazaResult;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PlazaGamesTests {
    @Test
    void observationsCarryLiteralNextActionsWithoutDisclosingStateOrReplacingPlatformStatus() {
        var json = JsonMapper.shared();
        var games = mock(GameSaves.class);
        var actor = mock(AuthPrincipal.class);
        var workspace = WorkspaceId.random();
        var save = UUID.randomUUID();
        String action = "wish \"more\" \\ ; $(echo hi)";
        var result = new GameSaves.View(
                save,
                "2",
                "sha256:" + "a".repeat(64),
                "Puzzle",
                workspace.value(),
                UUID.randomUUID(),
                new GameRunner.Result(
                        json.createObjectNode().put("hiddenSolution", "private game state"),
                        new GameRunner.Observation(
                                "[ok] FREE_CANDY", List.of(new GameRunner.Action(action, "Try")), false),
                        null));
        when(games.play(actor, workspace, "street/game", "1")).thenReturn(result);
        when(games.press(actor, workspace, save, action, "2")).thenReturn(result);
        var entrance = new PlazaGames(games);
        PlazaResult started = entrance.execute(actor, workspace, PlazaCommand.parse("play street/game 1"));
        assertThat(started.status()).isEqualTo(new PlazaResult.Status("ok", "OK"));
        JsonNode output = json.valueToTree(started.data());
        assertThat(output.has("state")).isFalse();
        assertThat(output.toString()).doesNotContain("hiddenSolution");
        assertThat(output.path("observation").path("text").stringValue()).isEqualTo("[ok] FREE_CANDY");
        PlazaCommand next = PlazaCommand.parse(started.next().getFirst());
        assertThat(next.name()).isEqualTo("press");
        assertThat(next.arguments()).containsExactly(save.toString(), action, "2");
        entrance.execute(actor, workspace, next);
        verify(games).press(actor, workspace, save, action, "2");
    }
}
