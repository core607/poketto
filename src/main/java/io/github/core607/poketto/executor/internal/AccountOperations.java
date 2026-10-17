package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.SiteGroup;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Concurrent commands per account, bounded by the account's site group. Waiting callers are woken
 * when any account releases a slot; the group is read when a command asks for one, so a group change
 * applies from the next command. Groups without a limit take no slot and skip the lookup entirely.
 */
final class AccountOperations {
    private final Map<SiteGroup, Integer> limits;
    private final Function<AuthPrincipal, SiteGroup> groups;
    private final Map<UUID, Integer> running = new HashMap<>();

    AccountOperations(Map<SiteGroup, Integer> limits, Function<AuthPrincipal, SiteGroup> groups) {
        this.limits = Map.copyOf(limits);
        this.groups = groups;
    }

    /** A held slot, or null when the account is still at its limit once {@code timeout} has passed. */
    Slot acquire(AuthPrincipal principal, Duration timeout) throws InterruptedException {
        Integer limit = limits.isEmpty() ? null : limits.get(groups.apply(principal));
        if (limit == null) {
            return () -> {};
        }
        UUID account = principal.accountId();
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (this) {
            while (running.getOrDefault(account, 0) >= limit) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return null;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            running.merge(account, 1, Integer::sum);
        }
        return () -> release(account);
    }

    private synchronized void release(UUID account) {
        running.computeIfPresent(account, (key, value) -> value == 1 ? null : value - 1);
        notifyAll();
    }

    /** Releases the slot once; an unlimited group's slot releases nothing. */
    interface Slot extends AutoCloseable {
        @Override
        void close();
    }
}
