package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.MachineAccounts;
import io.github.core607.poketto.auth.MachinePermission;
import java.util.Set;
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

/** Browser-session consent by the credential holder, never by a workspace key manager. */
@RestController
@RequestMapping("/api/auth/account/machine-grants")
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class MachineAccountController {
    private final MachineAccounts accounts;

    MachineAccountController(MachineAccounts accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    MachineAccounts.ConnectionPage connections(
            @AuthenticationPrincipal AuthPrincipal actor, @RequestParam(defaultValue = "0") int offset) {
        return accounts.connections(actor, offset);
    }

    @PutMapping("/{key}")
    Grant set(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable UUID key, @RequestBody Change input) {
        return new Grant(accounts.change(actor, key, input.permission(), input.enabled()));
    }

    record Change(MachinePermission permission, Boolean enabled) {
        Change {
            if (permission == null || enabled == null) {
                throw new IllegalArgumentException("Select one permission and its enabled state");
            }
        }
    }

    record Grant(Set<MachinePermission> permissions) {
        Grant {
            if (permissions == null) {
                throw new IllegalArgumentException("Permissions are required");
            }
            permissions = Set.copyOf(permissions);
        }
    }
}
