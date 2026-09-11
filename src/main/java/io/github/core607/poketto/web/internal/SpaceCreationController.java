package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.RepositoryConnectionException;
import io.github.core607.poketto.spaces.SpaceCreationService;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/workspaces")
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class SpaceCreationController {
    private final SpaceCreationService creation;

    SpaceCreationController(SpaceCreationService creation) {
        this.creation = creation;
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
            @PathVariable String workspaceId,
            @RequestBody CredentialRequest body) {
        WorkspaceId workspace = WorkspaceId.parse(workspaceId);
        creation.rotateCredentials(actor, workspace, body.username(), body.token());
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
