package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.CreatorApplications;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** An account asks for creator eligibility; site administrators decide. */
@RestController
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class CreatorApplicationController {
    private final CreatorApplications applications;

    CreatorApplicationController(CreatorApplications applications) {
        this.applications = applications;
    }

    @GetMapping("/api/auth/creator-application")
    CreatorApplications.Standing standing(@AuthenticationPrincipal AuthPrincipal actor) {
        return applications.standing(actor);
    }

    @PostMapping("/api/auth/creator-application")
    CreatorApplications.Application apply(
            @AuthenticationPrincipal AuthPrincipal actor, @RequestBody Statement request) {
        return applications.apply(actor, request.statement());
    }

    @GetMapping("/api/auth/site/creator-applications")
    AuthService.Page<CreatorApplications.Queued> list(
            @AuthenticationPrincipal AuthPrincipal actor,
            @RequestParam(defaultValue = "OPEN") String status,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        return applications.list(actor, CreatorApplications.Status.parse(status), offset, limit);
    }

    @PostMapping("/api/auth/creator-application/{applicationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markRead(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable UUID applicationId) {
        applications.markRead(actor, applicationId);
    }

    @PostMapping("/api/auth/site/creator-applications/{applicationId}/decision")
    CreatorApplications.Application decide(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable UUID applicationId,
            @RequestBody Decision request) {
        return applications.decide(actor, applicationId, request.approve(), request.note());
    }

    record Statement(String statement) {}

    record Decision(boolean approve, String note) {}
}
