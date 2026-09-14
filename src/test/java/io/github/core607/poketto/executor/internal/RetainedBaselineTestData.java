package io.github.core607.poketto.executor.internal;

import java.nio.file.Path;
import java.util.UUID;

/** Metadata-only fixtures use a synthetic descriptor; storage and native tests capture real archives. */
final class RetainedBaselineTestData {
    private RetainedBaselineTestData() {}

    static RetainedBaseline.Reference reference(RetainedCopyRecord.Owner owner, UUID copy, String commit, long expiry) {
        return new RetainedBaseline.Reference(
                new RetainedBaseline.Identity(owner, copy, commit, expiry), "e".repeat(64), 128, 0);
    }

    static RetainedWorkStores stores(RetainedCopyStore records, Path originals) {
        return new RetainedWorkStores(
                records,
                new RetainedBaselineStore(
                        originals,
                        records,
                        new RetainedBaselineStore.Limits(
                                16,
                                new RetainedBaseline.Limits(8 * 1024 * 1024, 16 * 1024 * 1024, 10000),
                                64 * 1024 * 1024,
                                0)));
    }
}
