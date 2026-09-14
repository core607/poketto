package io.github.core607.poketto.executor.internal;

/** Publishes prospective baseline and receipt state in the durable account journal. */
@FunctionalInterface
interface SaveStateJournal {
    /** No durable journal has been bound to this state yet. */
    SaveStateJournal NONE = state -> {};

    void retain(RetainedSaveState state);
}
