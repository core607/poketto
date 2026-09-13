package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the writer lock from admission through command acknowledgement or confirmed containment. */
final class RetainedCommand implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RetainedCommand.class);
    private final RetainedCopyStore store;
    private final RetainedCopyRecord.Owner owner;
    private final UUID copyId;
    private final RetainedFileLocks.Held writer;
    private final Port port;
    private RetainedCopyRecord record;
    private UUID executionId;
    private boolean resumed;
    private RetainedCopyRecord.Checkpoint resumedPoint;

    RetainedCommand(
            RetainedCopyStore store,
            RetainedCopyRecord.Owner owner,
            UUID copyId,
            RetainedCopyRecord expected,
            Port port) {
        this(store, owner, copyId, expected, null, port);
    }

    static RetainedCommand resume(
            RetainedCopyStore store, RetainedCopyRecord.Owner owner, UUID copyId, long expectedGeneration, Port port) {
        return new RetainedCommand(store, owner, copyId, null, expectedGeneration, port);
    }

    private RetainedCommand(
            RetainedCopyStore store,
            RetainedCopyRecord.Owner owner,
            UUID copyId,
            RetainedCopyRecord expected,
            Long expectedGeneration,
            Port port) {
        this.store = Objects.requireNonNull(store, "retained store must be present");
        this.owner = Objects.requireNonNull(owner, "retained owner must be present");
        this.copyId = Objects.requireNonNull(copyId, "retained copy identity must be present");
        this.port = Objects.requireNonNull(port, "worker checkpoint port must be present");
        writer = store.writer(owner, copyId);
        try {
            if (expectedGeneration != null) {
                record = store.read(owner, copyId);
                requireGeneration(expectedGeneration);
            } else if (expected != null) {
                record = store.read(owner, copyId);
                requireExpected(expected);
            }
        } catch (RuntimeException failure) {
            try {
                writer.close();
            } catch (IOException closing) {
                failure.addSuppressed(closing);
            }
            log.warn("Retained command admission failed", failure);
            if (failure instanceof ExecutionAdmissionException admission) {
                throw admission;
            }
            throw failure instanceof RetainedCopyException retained
                    ? retained
                    : new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE, failure);
        }
    }

    private void requireExpected(RetainedCopyRecord expected) {
        if (!owner.equals(expected.owner()) || !copyId.equals(expected.copyId())) {
            throw new RetainedCopyException(RetainedCopyException.Reason.STALE);
        }
        if (record.revision() != expected.revision() || record.generation() != expected.generation()) {
            throw new RetainedCopyException(RetainedCopyException.Reason.STALE);
        }
        if (!record.transportHash().equals(expected.transportHash())
                || !record.writer().equals(expected.writer())) {
            throw new RetainedCopyException(RetainedCopyException.Reason.STALE);
        }
        if (record.command() != null) {
            throw new RetainedCopyException(RetainedCopyException.Reason.UNCERTAIN);
        }
    }

    void initialize(
            String transportHash,
            boolean fullRead,
            RepositorySnapshotExports.PublicExport publicExport,
            RetainedCopyRecord.Writer lease,
            RetainedSaveState state,
            RetainedBaselineStore originals,
            Consumer<Consumer<RepositoryFile>> source) {
        if (record != null) {
            return;
        }
        long expiresAt = store.newExpiry();
        RetainedBaseline.Reference original = fullRead
                ? originals.capture(
                        writer, new RetainedBaseline.Identity(owner, copyId, state.originalCommit(), expiresAt), source)
                : null;
        RetainedCopyRecord.Checkpoint checkpoint = capture(state, expiresAt, Optional.empty());
        var initial = new RetainedCopyRecord(
                1,
                owner,
                copyId,
                0,
                1,
                transportHash,
                fullRead,
                publicExport,
                expiresAt,
                lease,
                checkpoint,
                null,
                null,
                original);
        publish(initial);
    }

    void begin(UUID id) {
        Objects.requireNonNull(id, "command identity must be present");
        if (record == null || executionId != null || (record.command() != null && !resumed)) {
            throw new RetainedCopyException(RetainedCopyException.Reason.UNCERTAIN);
        }
        replace(
                record.acknowledged(),
                new RetainedCopyRecord.Command(
                        id,
                        RetainedCopyRecord.Outcome.RUNNING,
                        resumedPoint == null ? record.acknowledged() : resumedPoint));
        executionId = id;
    }

    void retain(RetainedSaveState state) {
        if (executionId == null) {
            throw new RetainedCopyException(RetainedCopyException.Reason.UNCERTAIN);
        }
        RetainedCopyRecord.Checkpoint previous = record.command().checkpoint();
        RetainedCopyRecord.Checkpoint next = capture(state, record.expiresAt(), Optional.of(executionId));
        replace(
                record.acknowledged(),
                new RetainedCopyRecord.Command(executionId, RetainedCopyRecord.Outcome.RUNNING, next));
        if (!previous.id().equals(record.acknowledged().id())) {
            forget(previous);
        }
    }

    void complete(RetainedSaveState state) {
        if (executionId == null) {
            throw new RetainedCopyException(RetainedCopyException.Reason.UNCERTAIN);
        }
        RetainedCopyRecord.Checkpoint previous = record.acknowledged();
        RetainedCopyRecord.Checkpoint intermediate = record.command().checkpoint();
        RetainedCopyRecord.Checkpoint next = capture(state, record.expiresAt(), Optional.empty());
        replace(next, null);
        executionId = null;
        forget(previous);
        if (!intermediate.id().equals(previous.id())) {
            forget(intermediate);
        }
    }

    private RetainedCopyRecord.Checkpoint capture(RetainedSaveState state, long expiresAt, Optional<UUID> running) {
        UUID id = UUID.randomUUID();
        try {
            WorkerResponses.CheckpointReply reply = port.capture(id, expiresAt, running);
            WorkerResponses.CheckpointDescriptor checkpoint = reply.checkpoint();
            if (!checkpoint.checkpointId().equals(id.toString()) || checkpoint.expiresAt() != expiresAt) {
                throw new WorkerUnavailableException();
            }
            if (!reply.commit().equals(state.originalCommit())) {
                throw new WorkerUnavailableException();
            }
            if (!Objects.equals(reply.executionId(), running.map(UUID::toString).orElse(null))) {
                throw new WorkerUnavailableException();
            }
            return new RetainedCopyRecord.Checkpoint(id, checkpoint.sha256(), checkpoint.bytes(), state);
        } catch (RuntimeException failure) {
            log.warn("Worker checkpoint was not acknowledged", failure);
            throw new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE, failure);
        }
    }

    private void replace(RetainedCopyRecord.Checkpoint acknowledged, RetainedCopyRecord.Command command) {
        var next = new RetainedCopyRecord(
                record.format(),
                owner,
                copyId,
                record.revision() + 1,
                record.generation(),
                record.transportHash(),
                record.fullRead(),
                record.publicExport(),
                record.expiresAt(),
                record.writer(),
                acknowledged,
                command,
                record.lastInterruptedCommand(),
                record.originalBaseline());
        publish(next);
    }

    private void publish(RetainedCopyRecord next) {
        try {
            if (record == null) {
                store.create(next);
            } else {
                store.replace(record.revision(), record.generation(), next);
            }
            record = next;
        } catch (RuntimeException failure) {
            log.warn("Retained command state was not acknowledged", failure);
            throw failure instanceof RetainedCopyException retained
                    ? retained
                    : new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE, failure);
        }
    }

    private void forget(RetainedCopyRecord.Checkpoint checkpoint) {
        try {
            port.remove(checkpoint);
        } catch (RuntimeException failure) {
            // Publication already succeeded; a cleanup failure must not revoke the new recovery point.
            log.warn("Superseded worker checkpoint could not be removed", failure);
        }
    }

    RetainedCopyRecord record() {
        return record;
    }

    void requireGeneration(Long expectedGeneration) {
        if (expectedGeneration == null) {
            throw new IllegalArgumentException("Continuing a retained copy requires its expected generation");
        }
        if (record.generation() != expectedGeneration) {
            throw new ExecutionAdmissionException(
                    ExecutionAdmissionException.Reason.GENERATION_MISMATCH, record.generation(), true);
        }
    }

    /** Caller holds this command's writer lock and has already confirmed the prior lease's containment. */
    void transferAfterContainment(String transportHash, RetainedCopyRecord.Writer nextWriter) {
        if (record.generation() == RetainedCopyRecord.MAX_VERSION || executionId != null || resumed) {
            throw new RetainedCopyException(RetainedCopyException.Reason.LIMIT);
        }
        RetainedCopyRecord.Command previous = record.command();
        RetainedCopyRecord.Checkpoint point = previous == null ? record.acknowledged() : previous.checkpoint();
        RetainedCopyRecord.Command interrupted = previous == null
                ? null
                : new RetainedCopyRecord.Command(previous.id(), RetainedCopyRecord.Outcome.INTERRUPTED, point);
        var next = new RetainedCopyRecord(
                record.format(),
                owner,
                copyId,
                record.revision() + 1,
                record.generation() + 1,
                transportHash,
                record.fullRead(),
                record.publicExport(),
                record.expiresAt(),
                nextWriter,
                record.acknowledged(),
                interrupted,
                previous == null ? record.lastInterruptedCommand() : previous.id(),
                record.originalBaseline());
        publish(next);
        resumedPoint = point;
        resumed = true;
    }

    RetainedCopyRecord.Checkpoint resumedPoint() {
        return Objects.requireNonNull(resumedPoint, "a retained point must be selected before restoration");
    }

    RepositoryExecutor.CopyRetention view() {
        return new RepositoryExecutor.CopyRetention(
                record.generation(), record.expiresAt(), resumed, record.lastInterruptedCommand());
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException failure) {
            log.warn("Retained writer lock could not be released", failure);
            throw new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE, failure);
        }
    }

    interface Port {
        WorkerResponses.CheckpointReply capture(UUID checkpointId, long expiresAt, Optional<UUID> executionId);

        void remove(RetainedCopyRecord.Checkpoint checkpoint);
    }
}
