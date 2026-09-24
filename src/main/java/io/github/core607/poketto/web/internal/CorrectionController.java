package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.community.Community;
import io.github.core607.poketto.community.CommunityException;
import io.github.core607.poketto.community.Corrections;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Reader proposals under the community routes; review under the space's authenticated routes. */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class CorrectionController {
    private static final String SPACE = "/api/auth/community/spaces/{space}/corrections";
    private static final String REVIEW = "/api/auth/workspaces/{workspaceId}/corrections";
    private final Corrections corrections;

    CorrectionController(Corrections corrections) {
        this.corrections = corrections;
    }

    @PostMapping(SPACE)
    Created propose(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable String space,
            @RequestBody Corrections.Proposal proposal) {
        return new Created(corrections.propose(actor, space, proposal));
    }

    /** 204 when the caller never proposed a correction for the route. */
    @GetMapping(SPACE + "/mine")
    ResponseEntity<Corrections.Mine> mine(
            @AuthenticationPrincipal AuthPrincipal actor, @PathVariable String space, @RequestParam String route) {
        return corrections
                .mine(actor, space, route)
                .map(mine ->
                        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mine))
                .orElseGet(() -> ResponseEntity.noContent()
                        .cacheControl(CacheControl.noStore())
                        .build());
    }

    @DeleteMapping("/api/auth/community/corrections/{correctionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void withdraw(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable UUID correctionId) {
        corrections.withdraw(actor, correctionId);
    }

    @GetMapping("/api/public/community/spaces/{space}/corrections/credits")
    ResponseEntity<Credits> credits(@PathVariable String space, @RequestParam String route) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new Credits(corrections.credits(space, route)));
    }

    @GetMapping(REVIEW)
    ResponseEntity<Community.Page<Corrections.Review>> open(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable String workspaceId,
            @RequestParam(defaultValue = "0") long before) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(corrections.open(actor, WorkspaceId.parse(workspaceId), before));
    }

    @PostMapping(REVIEW + "/{correctionId}/accept")
    Accepted accept(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable String workspaceId,
            @PathVariable UUID correctionId) {
        return new Accepted(corrections.accept(actor, WorkspaceId.parse(workspaceId), correctionId));
    }

    @PostMapping(REVIEW + "/{correctionId}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void decline(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable String workspaceId,
            @PathVariable UUID correctionId) {
        corrections.decline(actor, WorkspaceId.parse(workspaceId), correctionId);
    }

    @ExceptionHandler(CommunityException.class)
    ProblemDetail failure(CommunityException failure) {
        return CommunityController.problem(failure);
    }

    record Created(UUID id) {}

    record Credits(List<String> names) {}

    record Accepted(Corrections.Resolution result) {}
}
