package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.EmailAccounts;
import io.github.core607.poketto.auth.EmailAddress;
import io.github.core607.poketto.auth.EmailChallenges;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/identity")
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class EmailIdentityController {
    private final EmailAccounts accounts;
    private final EmailChallenges challenges;

    EmailIdentityController(EmailAccounts accounts, EmailChallenges challenges) {
        this.accounts = accounts;
        this.challenges = challenges;
    }

    @GetMapping("/policy")
    Policy policy() {
        return new Policy(challenges.available());
    }

    @PostMapping("/signup/challenge")
    EmailChallenges.Receipt signupChallenge(@RequestBody Address request, HttpServletRequest http) {
        return accounts.requestSignup(request.email(), http.getRemoteAddr());
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    Created signup(@RequestBody Signup request) {
        AuthPrincipal account = accounts.signup(request.proof(), request.password(), request.displayName());
        return new Created(account.accountId());
    }

    @PostMapping("/email/challenge")
    EmailChallenges.Receipt bindingChallenge(
            @AuthenticationPrincipal AuthPrincipal actor, @RequestBody Address request, HttpServletRequest http) {
        return accounts.requestBinding(actor, request.email(), http.getRemoteAddr());
    }

    @PutMapping("/email")
    EmailAccounts.Profile bind(@AuthenticationPrincipal AuthPrincipal actor, @RequestBody EmailChallenges.Proof proof) {
        return accounts.bind(actor, proof);
    }

    @PostMapping("/recovery/challenge")
    EmailChallenges.Receipt recoveryChallenge(@RequestBody Address request, HttpServletRequest http) {
        return accounts.requestRecovery(request.email(), http.getRemoteAddr());
    }

    @PostMapping("/recovery")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void recover(@RequestBody Password request) {
        accounts.resetPassword(request.proof(), request.password());
    }

    @GetMapping("/account")
    EmailAccounts.Profile profile(@AuthenticationPrincipal AuthPrincipal actor) {
        return accounts.profile(actor);
    }

    @PutMapping("/account")
    EmailAccounts.Profile rename(@AuthenticationPrincipal AuthPrincipal actor, @RequestBody DisplayName request) {
        return accounts.rename(actor, request.displayName());
    }

    record Policy(boolean emailAvailable) {}

    record Created(UUID accountId) {}

    record Address(String email) {
        Address {
            email = EmailAddress.normalize(email);
        }

        @Override
        public String toString() {
            return "EmailAddress[REDACTED]";
        }
    }

    record Signup(EmailChallenges.Proof proof, String password, String displayName) {
        Signup {
            Objects.requireNonNull(proof, "Email proof is required");
            requirePassword(password);
            requireName(displayName);
        }

        @Override
        public String toString() {
            return "Signup[REDACTED]";
        }
    }

    record Password(EmailChallenges.Proof proof, String password) {
        Password {
            Objects.requireNonNull(proof, "Email proof is required");
            requirePassword(password);
        }

        @Override
        public String toString() {
            return "PasswordRecovery[REDACTED]";
        }
    }

    record DisplayName(String displayName) {
        DisplayName {
            requireName(displayName);
        }
    }

    private static void requirePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 256) {
            throw new IllegalArgumentException("Password must contain 12 to 256 characters");
        }
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 120) {
            throw new IllegalArgumentException("Display name must contain 1 to 120 characters");
        }
    }
}
