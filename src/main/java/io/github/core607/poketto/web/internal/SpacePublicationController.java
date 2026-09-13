package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.spaces.SpacePublicationService;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/workspaces/{workspaceId}/publication")
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class SpacePublicationController {
    private final SpacePublicationService publications;

    SpacePublicationController(SpacePublicationService publications) {
        this.publications = publications;
    }

    @GetMapping
    ResponseEntity<PublicationResponse> settings(
            @AuthenticationPrincipal AuthPrincipal actor, @PathVariable String workspaceId) {
        return response(publications.settings(actor, WorkspaceId.parse(workspaceId)));
    }

    @PutMapping
    ResponseEntity<PublicationResponse> update(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable String workspaceId,
            @RequestBody UpdatePublication request) {
        return response(publications.setEnabled(actor, WorkspaceId.parse(workspaceId), request.enabled()));
    }

    private static ResponseEntity<PublicationResponse> response(WorkspacePublications.Publication body) {
        var response =
                new PublicationResponse(body.workspaceId().toString(), body.slug(), body.displayName(), body.enabled());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    record PublicationResponse(String workspaceId, String slug, String displayName, boolean enabled) {}

    record UpdatePublication(Boolean enabled) {
        UpdatePublication {
            if (enabled == null) {
                throw new IllegalArgumentException("Website delivery choice is required");
            }
        }
    }
}
