package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.RepositoryPatchResult;
import io.github.core607.poketto.content.RepositoryWriteAttempt;
import java.util.Set;

/** Remote move intent, its exact candidate, and acknowledged host-baseline progress. */
record NativeMoveState(
        NativePlanFile definition,
        boolean started,
        RepositoryWriteAttempt attempt,
        RepositoryPatchResult result,
        boolean conflicted,
        Set<String> baselined) {
    NativeMoveState {
        baselined = Set.copyOf(baselined);
        ProtocolValues.require(!started || definition != null, "move intent", "requires its immutable definition");
        ProtocolValues.require(attempt == null || started, "move attempt", "requires durable intent");
        ProtocolValues.require(
                result == null || (started && !conflicted), "move acknowledgement", "requires non-conflicting intent");
        ProtocolValues.require(
                baselined.isEmpty() || result != null, "move baselines", "require remote acknowledgement");
        if (result != null) {
            ProtocolValues.hex(result.commit(), 40, "acknowledged move commit");
        }
    }

    static NativeMoveState empty() {
        return new NativeMoveState(null, false, null, null, false, Set.of());
    }

    boolean recoveryRequired() {
        return started && result == null && !conflicted;
    }

    NativeMoveState starting() {
        return new NativeMoveState(definition, true, null, null, false, Set.of());
    }

    NativeMoveState withAttempt(RepositoryWriteAttempt value) {
        return new NativeMoveState(definition, started, value, result, conflicted, baselined);
    }

    NativeMoveState confirmed(RepositoryPatchResult value) {
        return new NativeMoveState(definition, started, attempt, value, false, baselined);
    }

    NativeMoveState refused() {
        return new NativeMoveState(definition, started, attempt, null, true, Set.of());
    }
}
