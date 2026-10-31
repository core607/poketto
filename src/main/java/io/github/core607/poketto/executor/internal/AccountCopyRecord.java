package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.RepositorySnapshotExports;
import java.util.Objects;
import java.util.UUID;

/** Account-owned disk work and its host write journal; OAuth grant identity belongs to the lease. */
record AccountCopyRecord(
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
        Objects.requireNonNull(owner, "copy owner must be present");
        Objects.requireNonNull(copyId, "copy ID must be present");
        ProtocolValues.inRange(revision, 0, ProtocolValues.MAX_SAFE_INTEGER - 1, "copy revision");
        ProtocolValues.inRange(expiresAt, 1, ProtocolValues.MAX_SAFE_INTEGER, "copy expiry");
        Objects.requireNonNull(writer, "writer identity must be present");
        Objects.requireNonNull(phase, "copy phase must be present");
        Objects.requireNonNull(state, "host save state must be present");
        state.requireRecoverable();
        ProtocolValues.require(
                (phase == Phase.RUNNING || phase == Phase.INTERRUPTED) == (executionId != null),
                "execution identity",
                "must match the command phase");
        RetainedPublicProjection.validate(owner.workspaceId(), owner.fullRead(), publicExport, state.originalCommit());
        ProtocolValues.require(
                phase != Phase.REFRESHING || !owner.fullRead(), "refreshing copy", "must be a public projection");
        if (!owner.fullRead()) {
            ProtocolValues.require(original == null, "public baseline", "must not contain a private archive");
        } else if (phase != Phase.INITIALIZING && phase != Phase.DISCARDING) {
            Objects.requireNonNull(original, "a ready full copy requires its immutable original baseline");
        }
    }

    AccountCopyRecord discarding() {
        if (phase == Phase.DISCARDING) {
            return this;
        }
        return new AccountCopyRecord(
                owner,
                copyId,
                revision + 1,
                expiresAt,
                writer,
                Phase.DISCARDING,
                null,
                executionId == null ? lastInterruptedCommand : executionId,
                state,
                publicExport,
                original);
    }

    /**
     * The account, workspace and reading scope a copy belongs to. {@code fork} is null for the
     * account's default copy of that scope, and otherwise the copy ID of a further copy opened
     * while the default was busy, so each fork has its own journal and lease.
     */
    record Owner(UUID accountId, UUID workspaceId, boolean fullRead, UUID fork) {
        Owner {
            Objects.requireNonNull(accountId, "owner account must be present");
            Objects.requireNonNull(workspaceId, "owner workspace must be present");
        }

        Owner(UUID accountId, UUID workspaceId, boolean fullRead) {
            this(accountId, workspaceId, fullRead, null);
        }

        Owner forked(UUID copyId) {
            return new Owner(accountId, workspaceId, fullRead, Objects.requireNonNull(copyId));
        }

        Owner withoutFork() {
            return new Owner(accountId, workspaceId, fullRead);
        }

        /** The ID a new copy of this owner receives: a fork's is fixed by its address. */
        UUID newCopyId() {
            return fork == null ? UUID.randomUUID() : fork;
        }
    }

    record Writer(UUID principalId, UUID workerBootId, UUID appBootId, UUID leaseId, NativeStorage storage) {
        Writer(UUID principalId, UUID workerBootId, UUID appBootId, UUID leaseId) {
            this(principalId, workerBootId, appBootId, leaseId, null);
        }

        Writer {
            Objects.requireNonNull(principalId, "writer grant must be present");
            Objects.requireNonNull(workerBootId, "worker epoch must be present");
            Objects.requireNonNull(appBootId, "application epoch must be present");
            Objects.requireNonNull(leaseId, "lease must be present");
        }
    }

    /** The application binds an account copy to ordinary Sandcrab storage; labels grant no authority. */
    record NativeStorage(UUID volumeId, String profile, long byteLimit, long inodeLimit) {
        NativeStorage {
            Objects.requireNonNull(volumeId, "native volume must be present");
            profile = SandcrabProtocol.profile(profile);
            ProtocolValues.inRange(byteLimit, 1_048_576, 1L << 50, "native volume byte limit");
            ProtocolValues.require(byteLimit % 1024 == 0, "native volume byte limit", "must be a multiple of 1024");
            ProtocolValues.inRange(inodeLimit, 32, 1_000_000, "native volume inode limit");
        }

        void requireVolume(SandcrabVolume volume) {
            ProtocolValues.require(volumeId.toString().equals(volume.id()), "native volume", "must match its copy");
            ProtocolValues.require(profile.equals(volume.profile()), "native profile", "must match its copy");
            ProtocolValues.require(byteLimit == volume.byteLimit(), "native byte limit", "must match its copy");
            ProtocolValues.require(inodeLimit == volume.inodeLimit(), "native inode limit", "must match its copy");
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

    /**
     * {@code REFRESHING} marks a clean public copy whose publication changed: its files are
     * disposable, and the next admission discards them and rebuilds the copy under the same ID from
     * current publication. It stays set until the rebuilt copy's first lease is ready, so an
     * interruption anywhere in the rebuild resumes it instead of losing the copy ID.
     */
    enum Phase {
        INITIALIZING,
        READY,
        RUNNING,
        INTERRUPTED,
        DISCARDING,
        REFRESHING
    }
}
