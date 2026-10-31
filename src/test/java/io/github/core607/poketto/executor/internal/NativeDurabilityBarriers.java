package io.github.core607.poketto.executor.internal;

import java.util.function.Consumer;

/** Test-only checkpoints surround the real journal publication; the host controller supplies SIGKILL. */
final class NativeDurabilityBarriers {
    private final String scenario;
    private final Consumer<AccountCopyRecord> stop;
    private boolean armed;

    NativeDurabilityBarriers(String scenario, Consumer<AccountCopyRecord> stop) {
        this.scenario = scenario;
        this.stop = stop;
    }

    void arm() {
        armed = true;
    }

    void write(AccountCopyRecord previous, AccountCopyRecord next, Runnable persist) {
        boolean completion = armed
                && previous != null
                && previous.phase() == AccountCopyRecord.Phase.RUNNING
                && next.phase() == AccountCopyRecord.Phase.READY;
        if (completion && scenario.equals("beforepublish")) {
            stop.accept(previous);
        }
        persist.run();
        if (completion && scenario.equals("afterpublish")) {
            stop.accept(next);
        }
    }

    void remove(AccountCopyRecord current, Runnable persist) {
        if (armed && scenario.equals("discarding")) {
            ProtocolValues.require(
                    current.phase() == AccountCopyRecord.Phase.DISCARDING,
                    "discard process barrier",
                    "requires the durable discard checkpoint");
            stop.accept(current);
        }
        persist.run();
    }
}
