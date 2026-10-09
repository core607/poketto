package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.games.GameBundle;
import io.github.core607.poketto.games.GameLibrary;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** This public entrance never calls a game runner, validates source or fetches Git. */
@RestController
@RequestMapping("/api/public/games/spaces/{space}/articles/{articleId}")
class GameController {
    private final ObjectProvider<GameLibrary> library;

    GameController(ObjectProvider<GameLibrary> library) {
        this.library = library;
    }

    @GetMapping
    ResponseEntity<Package> game(@PathVariable String space, @PathVariable UUID articleId) {
        GameLibrary available = library.getIfAvailable();
        if (available == null) {
            return ResponseEntity.notFound().build();
        }
        return available
                .find(space, articleId)
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(new Package(
                                value.workspaceId().value(),
                                value.articleId(),
                                value.title(),
                                value.version(),
                                value.help(),
                                value.bundle())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    record Package(UUID workspaceId, UUID articleId, String title, String version, String help, GameBundle bundle) {}

    @GetMapping("/status")
    ResponseEntity<Availability> status(@PathVariable String space, @PathVariable UUID articleId) {
        GameLibrary available = library.getIfAvailable();
        if (available == null) {
            return ResponseEntity.notFound().build();
        }
        return available
                .find(space, articleId)
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(new Availability(value.workspaceId().value(), value.articleId(), value.version())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    record Availability(UUID workspaceId, UUID articleId, String version) {}
}
