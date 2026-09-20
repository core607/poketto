package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AccountIdentity;
import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.AuthPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/account")
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class AccountController {
    private final Accounts accounts;

    AccountController(Accounts accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    AccountResponse account(@AuthenticationPrincipal AuthPrincipal actor) {
        return new AccountResponse(accounts.account(actor));
    }

    record AccountResponse(AccountIdentity account) {}
}
