package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class BrowserAuthController {
    private final AuthService auth;
    private final WorkspaceCatalog workspaces;

    BrowserAuthController(AuthService auth, WorkspaceCatalog workspaces) {
        this.auth = auth;
        this.workspaces = workspaces;
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

    @PostMapping("/initialize")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> initialize(@RequestBody InitializationRequest body) {
        return Map.of(
                "accountId",
                auth.initializeOwner(body.initializationToken(), body.login(), body.password())
                        .accountId());
    }

    @PostMapping("/invitations/register")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> register(@RequestBody InvitationRegistrationRequest body) {
        return Map.of(
                "accountId",
                auth.registerWithInvitation(body.token(), body.login(), body.password())
                        .accountId());
    }

    @PostMapping("/invitations/accept")
    Map<String, String> accept(
            @AuthenticationPrincipal AuthPrincipal principal, @RequestBody InvitationTokenRequest body) {
        return Map.of(
                "workspaceId", auth.acceptInvitation(principal, body.token()).toString());
    }

    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal AuthPrincipal principal) {
        WorkspaceAccess access =
                auth.authorize(principal, workspaces.defaultWorkspace().id());
        return new MeResponse(
                principal.accountId(),
                access.workspaceId().toString(),
                access.role().name(),
                access.capabilities().stream().map(Enum::name).sorted().toList());
    }

    record MeResponse(UUID accountId, String workspaceId, String role, java.util.List<String> capabilities) {}

    record InitializationRequest(String initializationToken, String login, String password) {
        @Override
        public String toString() {
            return "InitializationRequest[REDACTED]";
        }
    }

    record InvitationRegistrationRequest(String token, String login, String password) {
        @Override
        public String toString() {
            return "InvitationRegistrationRequest[REDACTED]";
        }
    }

    record InvitationTokenRequest(String token) {
        @Override
        public String toString() {
            return "InvitationTokenRequest[REDACTED]";
        }
    }
}
