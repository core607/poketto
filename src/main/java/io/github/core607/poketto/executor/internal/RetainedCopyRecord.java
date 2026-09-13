package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.ProtocolValues.require;

import java.util.Objects;
import java.util.UUID;

/** Durable ownership and the last complete checkpoint, kept alongside any interrupted host write. */
record RetainedCopyRecord(
        int format,
        Owner owner,
        UUID copyId,
        long revision,
        long generation,
        String transportHash,
        boolean fullRead,
        long expiresAt,
        Writer writer,
        Checkpoint acknowledged,
        Command command) {
    static final long MAX_VERSION = 9_007_199_254_740_991L;

    RetainedCopyRecord {
        require(format == 1, "retained copy format", "must be version 1");
        Objects.requireNonNull(owner, "retained copy owner must be present");
        Objects.requireNonNull(copyId, "retained copy id must be present");
        ProtocolValues.inRange(revision, 0, MAX_VERSION, "record revision");
        ProtocolValues.inRange(generation, 1, MAX_VERSION, "writer generation");
        transportHash = ProtocolValues.hex(transportHash, 64, "transport hash");
        require(expiresAt > 0, "retention expiry", "must be positive");
        Objects.requireNonNull(writer, "retained writer lease must be present");
        Objects.requireNonNull(acknowledged, "acknowledged checkpoint must be present");
        if (command != null) {
            require(
                    command.checkpoint()
                            .state()
                            .originalCommit()
                            .equals(acknowledged.state().originalCommit()),
                    "command state",
                    "must retain the original checkpoint commit");
        }
    }

    record Owner(UUID subjectId, UUID workspaceId) {
        Owner {
            Objects.requireNonNull(subjectId, "retained subject must be present");
            Objects.requireNonNull(workspaceId, "retained workspace must be present");
        }
    }

    /** The latest admitted lease may be newer than the last completed worker checkpoint. */
    record Writer(UUID workerBootId, UUID leaseId) {
        Writer {
            Objects.requireNonNull(workerBootId, "writer worker boot must be present");
            Objects.requireNonNull(leaseId, "writer lease must be present");
        }
    }

    record Checkpoint(
            UUID id, String sha256, long bytes, RetainedSaveState state, BridgeReplies.RestoredReceipt lastImport) {
        Checkpoint {
            Objects.requireNonNull(id, "worker checkpoint id must be present");
            sha256 = ProtocolValues.hex(sha256, 64, "worker checkpoint digest");
            ProtocolValues.inRange(bytes, 1, 1024L * 1024 * 1024, "worker checkpoint bytes");
            Objects.requireNonNull(state, "checkpoint save state must be present");
            Objects.requireNonNull(lastImport, "checkpoint import receipt must be present");
        }
    }

    /** A recovered RUNNING record is uncertain until containment and the host write are reconciled. */
    record Command(UUID id, Outcome outcome, Checkpoint checkpoint) {
        Command {
            Objects.requireNonNull(id, "retained command id must be present");
            Objects.requireNonNull(outcome, "retained command outcome must be present");
            Objects.requireNonNull(checkpoint, "retained command must pair worker bytes with its host save state");
        }
    }

    enum Outcome {
        RUNNING,
        INTERRUPTED
    }
}
