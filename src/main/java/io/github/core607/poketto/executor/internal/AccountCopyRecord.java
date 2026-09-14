package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.RepositorySnapshotExports;
import java.util.Objects;
import java.util.UUID;

/** Account-owned disk work and its host write journal; OAuth grant identity belongs to the lease. */
record AccountCopyRecord(
        int format,
        Owner owner,
        UUID copyId,
        long revision,
        long expiresAt,
        Writer writer,
        Phase phase,
        UUID executionId,
        UUID lastInterruptedCommand,
        RetainedSaveState state,
        RepositorySnapshotExports.PublicExport publicExport,
        Original original) {
    AccountCopyRecord {
        ProtocolValues.require(format == 1, "account copy format", "must be 1");
        Objects.requireNonNull(owner, "copy owner must be present");
        Objects.requireNonNull(copyId, "copy ID must be present");
        ProtocolValues.inRange(revision, 0, RetainedCopyRecord.MAX_VERSION, "copy revision");
        ProtocolValues.inRange(expiresAt, 1, RetainedCopyRecord.MAX_VERSION, "copy expiry");
        Objects.requireNonNull(writer, "writer identity must be present");
        Objects.requireNonNull(phase, "copy phase must be present");
        Objects.requireNonNull(state, "host save state must be present");
        state.requireRecoverable();
        ProtocolValues.require(
                (phase == Phase.RUNNING || phase == Phase.INTERRUPTED) == (executionId != null),
                "execution identity",
                "must match the command phase");
        RetainedPublicProjection.validate(
                new RetainedCopyRecord.Owner(owner.accountId(), owner.workspaceId()),
                owner.fullRead(),
                publicExport,
                state.originalCommit());
        if (!owner.fullRead()) {
            ProtocolValues.require(original == null, "public baseline", "must not contain a private archive");
        } else if (phase != Phase.INITIALIZING) {
            Objects.requireNonNull(original, "a ready full copy requires its immutable original baseline");
        }
    }

    record Owner(UUID accountId, UUID workspaceId, boolean fullRead) {
        Owner {
            Objects.requireNonNull(accountId, "owner account must be present");
            Objects.requireNonNull(workspaceId, "owner workspace must be present");
        }
    }

    record Writer(UUID principalId, UUID workerBootId, UUID appBootId, UUID leaseId) {
        Writer {
            Objects.requireNonNull(principalId, "writer grant must be present");
            Objects.requireNonNull(workerBootId, "worker epoch must be present");
            Objects.requireNonNull(appBootId, "application epoch must be present");
            Objects.requireNonNull(leaseId, "lease must be present");
        }
    }

    /** The containing record supplies owner, copy ID and original commit; expiry is independent. */
    record Original(String sha256, long bytes, int entries) {
        Original {
            sha256 = ProtocolValues.hex(sha256, 64, "original archive digest");
            ProtocolValues.inRange(bytes, 64, 1024L * 1024 * 1024, "original archive bytes");
            ProtocolValues.inRange(entries, 0, 100_000, "original archive entries");
        }
    }

    enum Phase {
        INITIALIZING,
        READY,
        RUNNING,
        INTERRUPTED,
        DISCARDING
    }
}
