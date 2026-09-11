package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class BrowserAuthController {
    private final AuthService auth;
    private final WorkspaceCatalog catalog;

    BrowserAuthController(AuthService auth, WorkspaceCatalog catalog) {
        this.auth = auth;
        this.catalog = catalog;
    }

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of(
                "headerName",
                token.getHeaderName(),
                "parameterName",
                token.getParameterName(),
                "token",
                token.getToken());
    }

    @PostMapping("/invitations/accept")
    Map<String, String> accept(
            @AuthenticationPrincipal AuthPrincipal principal, @RequestBody InvitationTokenRequest body) {
        return Map.of(
                "workspaceId", auth.acceptInvitation(principal, body.token()).toString());
    }

    @GetMapping("/workspaces/{workspaceId}/me")
    MeResponse me(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String workspaceId) {
        WorkspaceAccess access = auth.authorize(principal, WorkspaceId.parse(workspaceId));
        return new MeResponse(
                principal.accountId(),
                access.workspaceId().toString(),
                catalog.findById(access.workspaceId()).orElseThrow().displayName(),
                access.role().name(),
                access.capabilities().stream().map(Enum::name).sorted().toList());
    }

    @GetMapping("/workspaces")
    AuthService.Page<SpaceResponse> workspaces(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        var page = auth.workspaces(principal, offset, limit);
        return new AuthService.Page<>(
                page.items().stream()
                        .map(value -> new SpaceResponse(
                                value.workspaceId().toString(),
                                value.displayName(),
                                value.access().role().name(),
                                value.access().capabilities().stream()
                                        .map(Enum::name)
                                        .sorted()
                                        .toList()))
                        .toList(),
                page.total(),
                page.offset(),
                page.limit());
    }

    record SpaceResponse(String workspaceId, String displayName, String role, java.util.List<String> capabilities) {}

    record MeResponse(
            UUID accountId, String workspaceId, String displayName, String role, java.util.List<String> capabilities) {}

    record InvitationTokenRequest(String token) {
        @Override
        public String toString() {
            return "InvitationTokenRequest[REDACTED]";
        }
    }
}
