package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AccountIdentity;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.RegistrationService;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class RegistrationController {
    private final RegistrationService registration;

    RegistrationController(RegistrationService registration) {
        this.registration = registration;
    }

    @GetMapping("/account")
    AccountResponse account(@AuthenticationPrincipal AuthPrincipal actor) {
        return new AccountResponse(registration.account(actor), registration.mayIssue(actor));
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> register(@RequestBody RegistrationRequest request) {
        return Map.of(
                "accountId",
                registration
                        .register(request.token(), request.login(), request.password())
                        .accountId());
    }

    @GetMapping("/registration-invitations")
    AuthService.Page<AuthService.InvitationInfo> invitations(
            @AuthenticationPrincipal AuthPrincipal actor,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        return registration.invitations(actor, offset, limit);
    }

    @PostMapping("/registration-invitations")
    @ResponseStatus(HttpStatus.CREATED)
    InvitationResponse issue(@AuthenticationPrincipal AuthPrincipal actor) {
        var issued = registration.issue(actor);
        return new InvitationResponse(issued.id(), issued.token());
    }

    @DeleteMapping("/registration-invitations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable UUID id) {
        registration.revoke(actor, id);
    }

    record AccountResponse(AccountIdentity account, boolean mayIssueRegistrationInvitations) {}

    record InvitationResponse(UUID id, String token) {
        @Override
        public String toString() {
            return "InvitationResponse[id=" + id + ", token=REDACTED]";
        }
    }

    record RegistrationRequest(String token, String login, String password) {
        @Override
        public String toString() {
            return "RegistrationRequest[REDACTED]";
        }
    }
}
