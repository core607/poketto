package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.RepositoryConnectionException;
import io.github.core607.poketto.content.RepositoryConnections;
import io.github.core607.poketto.spaces.SpaceCreationService;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/workspaces")
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class SpaceCreationController {
    private final SpaceCreationService creation;
    private final AuthService auth;
    private final RepositoryConnections repositories;

    SpaceCreationController(SpaceCreationService creation, AuthService auth, RepositoryConnections repositories) {
        this.creation = creation;
        this.auth = auth;
        this.repositories = repositories;
    }

    @GetMapping("/creation-policy")
    Map<String, Boolean> policy(@AuthenticationPrincipal AuthPrincipal actor) {
        return Map.of("available", creation.available(actor));
    }

    @PostMapping("/creations")
    SpaceCreationService.Result create(
            @AuthenticationPrincipal AuthPrincipal actor, @RequestBody CreationRequest body) {
        return creation.create(
                actor,
                body.requestId(),
                body.displayName(),
                body.slug(),
                body.repository(),
                body.username(),
                body.token());
    }

    @GetMapping("/creations/{requestId}")
    SpaceCreationService.Result status(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable UUID requestId) {
        return creation.status(actor, requestId);
    }

    @PutMapping("/{workspaceId}/repository-credentials")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void rotate(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable UUID workspaceId,
            @RequestBody CredentialRequest body) {
        if (actor == null || actor.kind() != AuthPrincipal.Kind.ACCOUNT)
            throw new AuthException(AuthException.Code.DENIED);
        WorkspaceId workspace = new WorkspaceId(workspaceId);
        auth.withAuthorization(actor, workspace, Set.of(Capability.MANAGE_KEYS), () -> {
            repositories.rotate(workspace, body.username(), body.token());
            return null;
        });
    }

    @ExceptionHandler(RepositoryConnectionException.class)
    ProblemDetail connection(RepositoryConnectionException error) {
        HttpStatus status =
                switch (error.code()) {
                    case BUSY -> HttpStatus.TOO_MANY_REQUESTS;
                    case INVALID_INPUT, PRIVATE_REPOSITORY_REQUIRED -> HttpStatus.BAD_REQUEST;
                    case DUPLICATE, REPOSITORY_CHANGED -> HttpStatus.CONFLICT;
                    case UNAVAILABLE, PERMISSION_DENIED -> HttpStatus.SERVICE_UNAVAILABLE;
                };
        var problem = ProblemDetail.forStatusAndDetail(status, "Repository connection could not be completed");
        problem.setProperty("code", error.code().name());
        return problem;
    }

    record CreationRequest(
            UUID requestId, String displayName, String slug, String repository, String username, String token) {
        @Override
        public String toString() {
            return "SpaceCreationRequest[redacted]";
        }
    }

    record CredentialRequest(String username, String token) {
        @Override
        public String toString() {
            return "RepositoryCredentialRequest[redacted]";
        }
    }
}
