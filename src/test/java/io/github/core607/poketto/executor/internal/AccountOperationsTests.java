package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.SiteGroup;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AccountOperationsTests {
    @Test
    void anAccountAtItsGroupLimitWaitsForItsOwnSlotWhileOtherAccountsProceed() throws Exception {
        var operations = new AccountOperations(Map.of(SiteGroup.CREATOR, 2), principal -> SiteGroup.CREATOR);
        var first = account();
        var held = operations.acquire(first, Duration.ZERO);
        operations.acquire(first, Duration.ZERO);

        assertThat(operations.acquire(first, Duration.ofMillis(50))).isNull();
        assertThat(operations.acquire(account(), Duration.ZERO)).isNotNull();

        var waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return operations.acquire(first, Duration.ofSeconds(5));
            } catch (InterruptedException interrupted) {
                throw new IllegalStateException(interrupted);
            }
        });
        held.close();
        assertThat(waiting.get(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void aGroupWithoutALimitTakesNoSlotAndAnEmptyQuotaSkipsTheLookup() throws Exception {
        var lookups = new AtomicInteger();
        var administrators = new AccountOperations(Map.of(SiteGroup.CREATOR, 1), principal -> {
            lookups.incrementAndGet();
            return SiteGroup.ADMINISTRATOR;
        });
        var administrator = account();
        for (int i = 0; i < 8; i++) {
            assertThat(administrators.acquire(administrator, Duration.ZERO)).isNotNull();
        }
        assertThat(lookups).hasValue(8);

        var unlimited = new AccountOperations(Map.of(), principal -> {
            throw new AssertionError("no quota needs no group");
        });
        assertThat(unlimited.acquire(administrator, Duration.ZERO)).isNotNull();
    }

    @Test
    void limitsExcludeAdministratorsAndStayWithinTheSiteCeiling() {
        assertThat(new ExecutionLimits(1024, 1024, Map.of(SiteGroup.VIEWER, 1), Map.of()).maxSessions())
                .isEqualTo(1024);
        assertThatThrownBy(() -> new ExecutionLimits(4, 4, Map.of(SiteGroup.ADMINISTRATOR, 1), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionLimits(4, 4, Map.of(SiteGroup.CREATOR, 5), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionLimits(1025, 4, Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionLimits(4, 0, Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AuthPrincipal account() {
        var principal = mock(AuthPrincipal.class);
        when(principal.accountId()).thenReturn(UUID.randomUUID());
        return principal;
    }
}
