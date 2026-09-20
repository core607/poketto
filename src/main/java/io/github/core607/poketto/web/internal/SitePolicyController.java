package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AccountIdentity;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.SiteGroup;
import io.github.core607.poketto.auth.SitePolicyService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Account administration supplies no workspace authority. */
@RestController
@RequestMapping("/api/auth/site/accounts")
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class SitePolicyController {
    private final SitePolicyService policies;

    SitePolicyController(SitePolicyService policies) {
        this.policies = policies;
    }

    @GetMapping
    AuthService.Page<SitePolicyService.AccountSummary> accounts(
            @AuthenticationPrincipal AuthPrincipal actor,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        return policies.list(actor, query, offset, limit);
    }

    @PutMapping("/{accountId}/group")
    AccountIdentity change(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable UUID accountId,
            @RequestBody GroupChange request) {
        return policies.change(actor, accountId, request.group(), request.reason());
    }

    @GetMapping("/{accountId}/group-history")
    AuthService.Page<SitePolicyService.Change> history(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        return policies.history(actor, accountId, offset, limit);
    }

    @GetMapping("/{accountId}/workspaces")
    AuthService.Page<SitePolicyService.OwnedSpace> workspaces(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        return policies.ownedSpaces(actor, accountId, offset, limit);
    }

    record GroupChange(SiteGroup group, String reason) {
        GroupChange {
            if (group == null) {
                throw new IllegalArgumentException("A site group is required");
            }
            if (reason == null || reason.isBlank() || reason.length() > 500) {
                throw new IllegalArgumentException("A reason of 1 to 500 characters is required");
            }
        }
    }
}
