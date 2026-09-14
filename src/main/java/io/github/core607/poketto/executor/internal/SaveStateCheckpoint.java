package io.github.core607.poketto.executor.internal;

/** Pairs this prospective host state with durable worker bytes before it becomes the live state. */
@FunctionalInterface
interface SaveStateCheckpoint {
    /** Ephemeral execution has no retained-work acknowledgement contract. */
    SaveStateCheckpoint UNTRACKED = state -> {};

    void retain(RetainedSaveState state);
}
