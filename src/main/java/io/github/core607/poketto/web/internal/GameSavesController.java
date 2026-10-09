package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.games.GameException;
import io.github.core607.poketto.games.GameSaves;
import io.github.core607.poketto.workspace.PublicationUnavailableException;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Browser cloud storage has no server execution endpoint. */
@RestController
@RequestMapping("/api/games/saves")
class GameSavesController {
    private final ObjectProvider<GameSaves> games;

    GameSavesController(ObjectProvider<GameSaves> games) {
        this.games = games;
    }

    @GetMapping
    GameSaves.Index list(@AuthenticationPrincipal AuthPrincipal actor) {
        return service().index(actor, null);
    }

    @GetMapping("/{id}")
    GameSaves.View load(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable UUID id) {
        return service().load(actor, id);
    }

    @PostMapping
    GameSaves.View store(@AuthenticationPrincipal AuthPrincipal actor, @RequestBody GameSaves.Upload input) {
        return service().store(actor, input);
    }

    @DeleteMapping("/{id}")
    Removal remove(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable UUID id) {
        return new Removal(service().remove(actor, null, id) ? "DELETED" : "ABSENT");
    }

    @ExceptionHandler(GameException.class)
    ProblemDetail failure(GameException failure) {
        HttpStatus status =
                switch (failure.code()) {
                    case "OWNER_CONSENT_REQUIRED" -> HttpStatus.FORBIDDEN;
                    case "GAME_UNAVAILABLE", "SAVE_UNAVAILABLE", "SAVE_REMOVED" -> HttpStatus.NOT_FOUND;
                    case "GAME_UPDATED", "SAVE_CONFLICT", "GAME_BUSY", "SAVE_CAPACITY" -> HttpStatus.CONFLICT;
                    case "GAME_FAILED" -> HttpStatus.UNPROCESSABLE_CONTENT;
                    default -> HttpStatus.BAD_REQUEST;
                };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Game save operation could not be completed");
        problem.setProperty("code", failure.code());
        return problem;
    }

    @ExceptionHandler({ContentRepositoryException.class, PublicationUnavailableException.class})
    ProblemDetail unavailable() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "The current game publication is unavailable");
    }

    private GameSaves service() {
        GameSaves result = games.getIfAvailable();
        if (result == null) {
            throw new GameException("GAME_UNAVAILABLE", "Games are not enabled on this instance");
        }
        return result;
    }

    record Removal(String result) {}
}
