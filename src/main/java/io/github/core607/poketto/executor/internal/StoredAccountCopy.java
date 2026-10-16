package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.content.RepositoryWriteAttempt;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The journal's disk form. Acknowledged file versions are stored once beside it and referenced by
 * digest, so a command that changes none of them rewrites only this small record; the per-path
 * commit index they imply is derived on restoration instead of being stored twice.
 */
record StoredAccountCopy(
        int format,
        AccountCopyRecord.Owner owner,
        UUID copyId,
        long revision,
        long expiresAt,
        AccountCopyRecord.Writer writer,
        AccountCopyRecord.Phase phase,
        UUID executionId,
        UUID lastInterruptedCommand,
        State state,
        RepositorySnapshotExports.PublicExport publicExport,
        AccountCopyRecord.Original original) {
    static final int FORMAT = 2;

    StoredAccountCopy {
        ProtocolValues.require(format == FORMAT, "account copy format", "must be " + FORMAT);
        Objects.requireNonNull(owner, "copy owner must be present");
        Objects.requireNonNull(copyId, "copy ID must be present");
        Objects.requireNonNull(phase, "copy phase must be present");
        Objects.requireNonNull(state, "host save state must be present");
    }

    static StoredAccountCopy of(AccountCopyRecord record, String fileBaselines) {
        var state = record.state();
        return new StoredAccountCopy(
                FORMAT,
                record.owner(),
                record.copyId(),
                record.revision(),
                record.expiresAt(),
                record.writer(),
                record.phase(),
                record.executionId(),
                record.lastInterruptedCommand(),
                new State(
                        state.originalCommit(),
                        state.baseCommit(),
                        fileBaselines,
                        state.uncertain(),
                        state.pending(),
                        state.attempt(),
                        state.move(),
                        state.lastSave(),
                        state.lastImport(),
                        state.sync()),
                record.publicExport(),
                record.original());
    }

    /** Validation happens in the restored record, exactly as for a record built in memory. */
    AccountCopyRecord restore(Map<String, RetainedFileBaseline> fileBaselines) {
        return new AccountCopyRecord(
                owner,
                copyId,
                revision,
                expiresAt,
                writer,
                phase,
                executionId,
                lastInterruptedCommand,
                state.restore(fileBaselines),
                publicExport,
                original);
    }

    /** {@link RetainedSaveState} without its file versions, which {@link #fileBaselines} names by digest. */
    record State(
            String originalCommit,
            String baseCommit,
            String fileBaselines,
            boolean uncertain,
            RepositoryPatch pending,
            RepositoryWriteAttempt attempt,
            RetainedSaveState.Move move,
            BridgeReplies.RestoredReceipt lastSave,
            BridgeReplies.RestoredReceipt lastImport,
            PendingWorkspaceSync sync) {
        State {
            fileBaselines = ProtocolValues.hex(fileBaselines, 64, "file baseline digest");
        }

        private RetainedSaveState restore(Map<String, RetainedFileBaseline> files) {
            Map<String, String> commits = files.entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(
                            Map.Entry::getKey, entry -> entry.getValue().commit()));
            return new RetainedSaveState(
                    originalCommit,
                    baseCommit,
                    commits,
                    files,
                    uncertain,
                    pending,
                    attempt,
                    move,
                    lastSave,
                    lastImport,
                    sync);
        }
    }
}
