package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Holds the exact writer until containment, including when the close attempt fails. */
final class RetainedDiscard implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RetainedDiscard.class);
    private final RetainedCopyStore store;
    private final RetainedFileLocks.Held writer;
    private final RetainedCopyRecord record;

    RetainedDiscard(RetainedCopyStore store, RetainedCopyRecord.Owner owner, UUID copyId, long generation) {
        this.store = store;
        writer = store.writer(owner, copyId);
        try {
            record = read(owner, copyId);
            if (record != null && record.generation() != generation) {
                throw new ExecutionAdmissionException(
                        ExecutionAdmissionException.Reason.GENERATION_MISMATCH,
                        record.generation(),
                        !store.expired(record.expiresAt()));
            }
        } catch (RuntimeException failure) {
            close();
            log.warn("Retained discard admission failed", failure);
            throw failure instanceof ExecutionAdmissionException admission
                    ? admission
                    : new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE, failure);
        }
    }

    private RetainedCopyRecord read(RetainedCopyRecord.Owner owner, UUID copyId) {
        try {
            return store.readForDiscard(writer, owner, copyId);
        } catch (RetainedCopyException missing) {
            if (missing.reason() != RetainedCopyException.Reason.MISSING) {
                throw missing;
            }
            return null;
        }
    }

    RetainedCopyRecord record() {
        return record;
    }

    void removeAfterContainment(CompletableFuture<Void> contained) {
        if (!contained.isDone() || contained.isCompletedExceptionally()) {
            throw new RetainedCopyException(RetainedCopyException.Reason.UNCERTAIN);
        }
        writer.requireValid();
        store.discard(record.owner(), record.copyId(), record.revision(), record.generation());
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException failure) {
            throw new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE, failure);
        }
    }
}
