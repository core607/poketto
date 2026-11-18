package io.github.core607.poketto.executor.internal;

import java.util.Map;

final class RetainedJournalFixture {
    private RetainedJournalFixture() {}

    static RetainedSaveState withFile(String original, String path, RetainedFileBaseline file) {
        var empty = BridgeReplies.RestoredReceipt.capture(new BridgeReplies.Absent());
        return new RetainedSaveState(
                original,
                file.commit(),
                Map.of(path, file.commit()),
                Map.of(path, file),
                false,
                null,
                null,
                null,
                empty,
                empty,
                null,
                null);
    }
}
