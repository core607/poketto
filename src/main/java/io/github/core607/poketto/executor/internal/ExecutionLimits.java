package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.SiteGroup;
import java.util.Map;

/**
 * The executor's admission ceilings: live leases and concurrent commands across the site, and per
 * account by site group, concurrent commands and the copies it may hold. A group without an entry,
 * such as administrators, is bounded only by the site-wide ceilings, which stand for the host's
 * capacity, and the copy store's own bound.
 */
record ExecutionLimits(
        int maxSessions,
        int maxOperations,
        Map<SiteGroup, Integer> accountOperations,
        Map<SiteGroup, Integer> accountCopies) {
    static final int CEILING = 1024;

    ExecutionLimits {
        accountOperations = Map.copyOf(accountOperations);
        accountCopies = Map.copyOf(accountCopies);
        if (accountCopies.containsKey(SiteGroup.ADMINISTRATOR)
                || accountCopies.values().stream().anyMatch(limit -> limit < 1 || limit > CEILING)) {
            throw new IllegalArgumentException("Invalid per-account copy bounds");
        }
        if (maxSessions < 1 || maxSessions > CEILING || maxOperations < 1 || maxOperations > CEILING) {
            throw new IllegalArgumentException("Invalid isolated executor bounds");
        }
        if (accountOperations.containsKey(SiteGroup.ADMINISTRATOR)
                || accountOperations.values().stream().anyMatch(limit -> limit < 1 || limit > maxOperations)) {
            throw new IllegalArgumentException("Invalid per-account execution bounds");
        }
    }

    /** Site-wide session bound with the default command ceiling and no per-account quota. */
    static ExecutionLimits sessions(int maxSessions) {
        return new ExecutionLimits(maxSessions, 4, Map.of(), Map.of());
    }
}
