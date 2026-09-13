package io.github.core607.poketto.executor.internal;

import java.util.Objects;

/** Retained execution requires metadata and its matching immutable original store together. */
record RetainedWorkStores(RetainedCopyStore records, RetainedBaselineStore originals) {
    RetainedWorkStores {
        Objects.requireNonNull(records, "retained metadata must be present");
        Objects.requireNonNull(originals, "retained originals must be present");
        originals.requireRecords(records);
    }
}
