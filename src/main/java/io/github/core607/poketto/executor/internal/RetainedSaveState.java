package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.ProtocolValues.require;

import io.github.core607.poketto.content.RepositoryMovePlan;
import io.github.core607.poketto.content.RepositoryMoveRequest;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryPatchResult;
import io.github.core607.poketto.content.RepositoryPaths;
import io.github.core607.poketto.content.RepositoryWriteAttempt;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Host write authority restored with a worktree checkpoint, never reconstructed from remote main. */
record RetainedSaveState(
        String originalCommit,
        String baseCommit,
        Map<String, String> baselines,
        Map<String, RetainedFileBaseline> fileBaselines,
        boolean uncertain,
        RepositoryPatch pending,
        RepositoryWriteAttempt attempt,
        Move move,
        BridgeReplies.RestoredReceipt lastSave,
        BridgeReplies.RestoredReceipt lastImport) {
    RetainedSaveState {
        originalCommit = commit(originalCommit);
        baseCommit = commit(baseCommit);
        baselines = Map.copyOf(baselines);
        require(baselines.size() <= 16384, "baselines", "must not exceed 16384 paths");
        baselines.forEach((path, revision) -> {
            RepositoryPaths.validate(path);
            commit(revision);
        });
        fileBaselines = Map.copyOf(fileBaselines);
        require(fileBaselines.size() <= 16384, "file baselines", "must not exceed 16384 paths");
        for (var entry : fileBaselines.entrySet()) {
            RepositoryPaths.validate(entry.getKey());
            require(
                    entry.getValue().commit().equals(baselines.get(entry.getKey())),
                    "file baseline",
                    "must match its tracked path commit");
        }
        require(uncertain == (pending != null), "pending save", "must match the uncertain state");
        require(attempt == null || uncertain, "save attempt", "requires a pending save");
        require(move == null || !uncertain, "pending move", "cannot coexist with a pending save");
        validatePending(pending, baseCommit);
        if (move != null) {
            require(move.request().baseCommit().equals(baseCommit), "pending move", "must retain its base commit");
        }
        Objects.requireNonNull(lastSave, "last save receipt must be present");
        Objects.requireNonNull(lastImport, "last import receipt must be present");
    }

    private static void validatePending(RepositoryPatch pending, String baseCommit) {
        if (pending == null) {
            return;
        }
        require(pending.baseCommit().equals(Optional.of(baseCommit)), "pending save", "must retain its base commit");
        long bytes = 0;
        for (var change : pending.changes()) {
            RepositoryPaths.validate(change.path());
            if (change.content().isPresent()) {
                bytes += change.content().orElseThrow().getBytes(StandardCharsets.UTF_8).length;
            }
        }
        require(bytes <= RepositoryPatch.MAX_BYTES, "pending save", "exceeds the patch byte budget");
    }

    private static String commit(String value) {
        return ProtocolValues.hex(value, 40, "retained commit");
    }

    /** Prepared local installation bytes and an exact uncertain or acknowledged remote move. */
    record Move(
            RepositoryMoveRequest request,
            byte[] payload,
            Set<String> paths,
            RepositoryWriteAttempt attempt,
            RepositoryPatchResult result,
            Map<String, RetainedFileBaseline> fileBaselines) {
        Move {
            Objects.requireNonNull(request, "retained move request must be present");
            Objects.requireNonNull(payload, "retained move payload must be present");
            require(
                    payload.length > 0 && payload.length <= 64 * 1024 * 1024,
                    "move payload",
                    "must be within the 64 MiB transfer budget");
            payload = payload.clone();
            paths = Set.copyOf(paths);
            require(
                    !paths.isEmpty() && paths.size() <= RepositoryMovePlan.MAX_CHANGED_PATHS,
                    "move paths",
                    "must be within the move path budget");
            paths.forEach(RepositoryPaths::validate);
            fileBaselines = Map.copyOf(fileBaselines);
            require(paths.containsAll(fileBaselines.keySet()), "move file baselines", "must belong to affected paths");
            for (var baseline : fileBaselines.values()) {
                require(
                        result != null && baseline.commit().equals(result.commit()),
                        "move file baseline",
                        "requires the acknowledged move commit");
            }
            if (result != null) {
                commit(result.commit());
                require(
                        attempt == null || attempt.commit().equals(result.commit()),
                        "move result",
                        "must match the retained attempt");
            }
        }

        static Move capture(SessionMoves.Pending pending) {
            return pending == null
                    ? null
                    : new Move(
                            pending.request,
                            pending.payload,
                            pending.paths,
                            pending.attempt,
                            pending.result,
                            pending.fileBaselines);
        }

        SessionMoves.Pending restore() {
            var pending = new SessionMoves.Pending(request, payload(), paths);
            pending.attempt = attempt;
            pending.result = result;
            pending.fileBaselines = fileBaselines;
            return pending;
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}
