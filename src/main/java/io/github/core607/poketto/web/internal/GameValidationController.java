package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.games.GameLibrary;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated inspection serves bounded metadata and never executes author code or fetches Git. */
@RestController
@RequestMapping("/api/auth/workspaces/{workspaceId}/games")
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class GameValidationController {
    private final AuthService auth;
    private final ObjectProvider<GameLibrary> games;

    GameValidationController(AuthService auth, ObjectProvider<GameLibrary> games) {
        this.auth = auth;
        this.games = games;
    }

    @GetMapping
    ResponseEntity<Inspection> inspect(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable String workspaceId) {
        WorkspaceId workspace = WorkspaceId.parse(workspaceId);
        return auth.withAuthorization(actor, workspace, Set.of(Capability.PUBLISH), () -> {
            GameLibrary library = games.getIfAvailable();
            if (library == null) {
                return response("DISABLED", null);
            }
            try {
                return response("AVAILABLE", library.inspect(workspace));
            } catch (ContentRepositoryException unavailable) {
                return response("PUBLICATION_UNAVAILABLE", null);
            }
        });
    }

    private static ResponseEntity<Inspection> response(String status, GameLibrary.Inspection inspection) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Inspection(status, inspection));
    }

    record Inspection(String status, GameLibrary.Inspection inspection) {}
}
