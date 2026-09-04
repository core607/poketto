package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.IssuedToken;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class WorkspaceAdminController {
    private final AuthService auth;
    private final WorkspaceCatalog workspaces;

    WorkspaceAdminController(AuthService auth, WorkspaceCatalog workspaces) {
        this.auth = auth;
        this.workspaces = workspaces;
    }

    @GetMapping("/members")
    List<AuthService.MemberInfo> members(@AuthenticationPrincipal AuthPrincipal principal) {
        return auth.listMembers(principal, workspaces.defaultWorkspace().id());
    }

    @PutMapping("/members/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void member(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID accountId,
            @RequestBody MembershipRequest body) {
        if (body.active() == null) throw new IllegalArgumentException("active is required");
        auth.changeMembership(principal, workspaces.defaultWorkspace().id(), accountId, body.role(), body.active());
    }

    @GetMapping("/invitations")
    List<AuthService.InvitationInfo> invitations(@AuthenticationPrincipal AuthPrincipal principal) {
        return auth.listInvitations(principal, workspaces.defaultWorkspace().id());
    }

    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    IssuedSecretResponse invite(@AuthenticationPrincipal AuthPrincipal principal) {
        return issued(
                auth.createInvitation(principal, workspaces.defaultWorkspace().id()));
    }

    @DeleteMapping("/invitations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeInvitation(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        auth.revokeInvitation(principal, workspaces.defaultWorkspace().id(), id);
    }

    @GetMapping("/keys")
    List<AuthService.ApiKeyInfo> keys(@AuthenticationPrincipal AuthPrincipal principal) {
        return auth.listApiKeys(principal, workspaces.defaultWorkspace().id());
    }

    @PostMapping("/keys")
    @ResponseStatus(HttpStatus.CREATED)
    IssuedSecretResponse key(@AuthenticationPrincipal AuthPrincipal principal, @RequestBody KeyRequest body) {
        if (body.accountId() == null
                || (body.capabilities() != null && body.capabilities().stream().anyMatch(java.util.Objects::isNull)))
            throw new IllegalArgumentException("key holder and capabilities must be valid");
        return issued(auth.createApiKey(
                principal, workspaces.defaultWorkspace().id(), body.accountId(), body.capabilities()));
    }

    @DeleteMapping("/keys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeKey(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        auth.revokeApiKey(principal, workspaces.defaultWorkspace().id(), id);
    }

    private static IssuedSecretResponse issued(IssuedToken token) {
        return new IssuedSecretResponse(token.id(), token.token());
    }

    record MembershipRequest(MembershipRole role, Boolean active) {}

    record KeyRequest(UUID accountId, Set<Capability> capabilities) {}

    record IssuedSecretResponse(UUID id, String token) {
        @Override
        public String toString() {
            return "IssuedSecretResponse[id=" + id + ", token=REDACTED]";
        }
    }
}
