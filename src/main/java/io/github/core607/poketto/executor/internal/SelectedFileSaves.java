package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.content.RepositoryMoveService;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryPatchResult;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
import io.github.core607.poketto.content.RepositoryWriteAttempt;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Uses only authoritative revisions; sandbox Git refs and indexes never supply write preconditions. */
final class SelectedFileSaves {
    private final AuthService auth;
    private final AuthorizedRepositoryReader reader;
    private final RepositoryPatchService patches;
    private final SessionMoves moves;

    SelectedFileSaves(
            AuthService auth,
            AuthorizedRepositoryReader reader,
            RepositoryPatchService patches,
            RepositoryMoveService moves) {
        this.auth = auth;
        this.reader = reader;
        this.patches = patches;
        this.moves = new SessionMoves(reader, moves);
    }

    SessionMoves moves() {
        return moves;
    }

    BridgeReplies.Reply save(
            AuthPrincipal actor, WorkspaceId workspace, State state, Map<String, String> writes, List<String> deletes) {
        auth.authorize(actor, workspace);
        if (state.move != null) {
            return SessionMoves.pendingResult(state.move, "RECOVER_MOVE_FIRST");
        }
        if (state.uncertain) {
            return BridgeReplies.failed("WRITE_OUTCOME_UNKNOWN");
        }
        RepositoryPatch patch = selectedPatch(actor, workspace, state, writes, deletes);
        prepareRetainedWrite(state, patch);
        try {
            RepositoryPatchResult result = state.tracked()
                    ? patches.apply(actor, workspace, patch, attempt -> retainAttempt(state, attempt))
                    : patches.apply(actor, workspace, patch);
            return completed(
                    state,
                    result,
                    patch.changes().stream().map(RepositoryTextChange::path).toList(),
                    false);
        } catch (RepositoryWriteAmbiguousException unknown) {
            State proposed = state.copy();
            proposed.pending = patch;
            proposed.attempt = unknown.attempt().isPresent() ? unknown.attempt() : proposed.attempt;
            proposed.uncertain = true;
            return remember(
                    state,
                    proposed,
                    BridgeReplies.failed(
                            "WRITE_OUTCOME_UNKNOWN",
                            "Run poketto recover to reconcile the retained commit before another save; local edits are retained."));
        } catch (RepositoryConflictException conflict) {
            return conflict(state, "Remote main changed; local edits and the host baseline are retained.");
        }
    }

    private RepositoryPatch selectedPatch(
            AuthPrincipal actor, WorkspaceId workspace, State state, Map<String, String> writes, List<String> deletes) {
        var paths = new HashSet<>(writes.keySet());
        if (writes.size() + deletes.size() < 1
                || writes.size() + deletes.size() > RepositoryPatch.MAX_CHANGES
                || deletes.stream().anyMatch(path -> !paths.add(path))) {
            throw new InvalidSelectionException(InvalidSelectionException.Reason.INVALID_ARGUMENTS);
        }
        var changes = new ArrayList<RepositoryTextChange>();
        state.requireTracking(paths);
        for (String path : paths) {
            String expectedCommit = state.baseline(path);
            var baseline = reader.getFile(actor, workspace, Optional.of(expectedCommit), path);
            if (!baseline.commit().equals(Optional.of(expectedCommit))
                    || (!baseline.expectedAbsence() && baseline.revision().isEmpty())) {
                throw new InvalidSelectionException(InvalidSelectionException.Reason.NO_WRITABLE_BASELINE);
            }
            changes.add(new RepositoryTextChange(
                    path, baseline.expectedAbsence(), baseline.revision(), Optional.ofNullable(writes.get(path))));
        }
        return new RepositoryPatch(Optional.of(state.baseCommit), changes);
    }

    private static void prepareRetainedWrite(State state, RepositoryPatch patch) {
        if (!state.tracked()) {
            return;
        }
        State proposed = state.copy();
        proposed.pending = patch;
        proposed.attempt = Optional.empty();
        proposed.uncertain = true;
        state.install(proposed);
    }

    private static void retainAttempt(State state, RepositoryWriteAttempt attempt) {
        State proposed = state.copy();
        proposed.attempt = Optional.of(attempt);
        state.install(proposed);
    }

    BridgeReplies.Reply recover(AuthPrincipal actor, WorkspaceId workspace, State state) {
        auth.authorize(actor, workspace);
        if (!state.uncertain) {
            return BridgeReplies.succeeded(new BridgeReplies.Recovery(false));
        }
        if (state.tracked() && state.attempt.isEmpty()) {
            // A tracked remote push cannot begin before its exact candidate was durably retained.
            State proposed = state.copy();
            proposed.uncertain = false;
            proposed.pending = null;
            return remember(state, proposed, BridgeReplies.succeeded(new BridgeReplies.Recovery(false)));
        }
        if (state.pending == null || state.attempt.isEmpty()) {
            return BridgeReplies.failed("WRITE_OUTCOME_UNKNOWN");
        }
        try {
            RepositoryPatchResult result = state.tracked()
                    ? patches.recover(
                            actor,
                            workspace,
                            state.pending,
                            state.attempt.orElseThrow(),
                            attempt -> retainAttempt(state, attempt))
                    : patches.recover(actor, workspace, state.pending, state.attempt.orElseThrow());
            return completed(
                    state,
                    result,
                    state.pending.changes().stream()
                            .map(RepositoryTextChange::path)
                            .toList(),
                    true);
        } catch (RepositoryWriteAmbiguousException unknown) {
            // Retain the same original patch and commit even if the recovery reply is also lost.
            return remember(state, state.copy(), BridgeReplies.failed("WRITE_OUTCOME_UNKNOWN"));
        } catch (RepositoryConflictException conflict) {
            return conflict(
                    state, "Remote main diverged from the retained attempt; local edits and baseline are retained.");
        }
    }

    private static BridgeReplies.Reply completed(
            State state, RepositoryPatchResult result, List<String> paths, boolean recovered) {
        State proposed = state.copy();
        proposed.baseCommit = result.commit();
        // Only these paths adopt the new remote baseline; unselected local edits keep theirs.
        paths.forEach(path -> proposed.baselines.put(path, result.commit()));
        proposed.uncertain = false;
        proposed.pending = null;
        proposed.attempt = Optional.empty();
        return remember(
                state,
                proposed,
                BridgeReplies.succeeded(new BridgeReplies.SaveResult(
                        result.commit(), result.committed(), result.snapshotUpdated(), paths, recovered)));
    }

    private static BridgeReplies.Reply conflict(State state, String message) {
        State proposed = state.copy();
        proposed.uncertain = false;
        proposed.pending = null;
        proposed.attempt = Optional.empty();
        return remember(state, proposed, BridgeReplies.failed("REPOSITORY_CONFLICT", message));
    }

    private static BridgeReplies.Reply remember(State state, State proposed, BridgeReplies.Reply reply) {
        proposed.lastSave = reply;
        state.install(proposed);
        return reply;
    }

    /** Confined to one session's admitted execute owner and its serial bridge loop; renewal never accesses it. */
    static final class State {
        private final String originalCommit;
        private final SaveStateCheckpoint checkpoint;
        private final Map<String, String> baselines = new HashMap<>();
        String baseCommit;
        boolean uncertain;
        RepositoryPatch pending;
        Optional<RepositoryWriteAttempt> attempt = Optional.empty();
        BridgeReplies.Recorded lastSave = new BridgeReplies.Absent();
        SessionMoves.Pending move;

        State(String baseCommit) {
            this(baseCommit, SaveStateCheckpoint.UNTRACKED);
        }

        State(String baseCommit, SaveStateCheckpoint checkpoint) {
            this.originalCommit = baseCommit;
            this.baseCommit = baseCommit;
            this.checkpoint = Objects.requireNonNull(checkpoint, "save checkpoint must be present");
        }

        boolean tracked() {
            return checkpoint != SaveStateCheckpoint.UNTRACKED;
        }

        State copy() {
            return restore(snapshot(), checkpoint);
        }

        void install(State proposed) {
            ProtocolValues.require(
                    originalCommit.equals(proposed.originalCommit), "save state", "must keep its original commit");
            if (tracked()) {
                checkpoint.retain(proposed.snapshot());
            }
            copyFields(proposed);
        }

        private void copyFields(State proposed) {
            baseCommit = proposed.baseCommit;
            baselines.clear();
            baselines.putAll(proposed.baselines);
            uncertain = proposed.uncertain;
            pending = proposed.pending;
            attempt = proposed.attempt;
            lastSave = proposed.lastSave;
            move = proposed.move;
        }

        RetainedSaveState snapshot() {
            return new RetainedSaveState(
                    originalCommit,
                    baseCommit,
                    baselines,
                    uncertain,
                    pending,
                    attempt.orElse(null),
                    RetainedSaveState.Move.capture(move),
                    BridgeReplies.RestoredReceipt.capture(lastSave));
        }

        static State restore(RetainedSaveState snapshot) {
            return restore(snapshot, SaveStateCheckpoint.UNTRACKED);
        }

        static State restore(RetainedSaveState snapshot, SaveStateCheckpoint checkpoint) {
            var state = new State(snapshot.originalCommit(), checkpoint);
            state.baseCommit = snapshot.baseCommit();
            state.baselines.putAll(snapshot.baselines());
            state.uncertain = snapshot.uncertain();
            state.pending = snapshot.pending();
            state.attempt = Optional.ofNullable(snapshot.attempt());
            state.move = snapshot.move() == null ? null : snapshot.move().restore();
            state.lastSave = snapshot.lastSave();
            return state;
        }

        String baseline(String path) {
            return baselines.getOrDefault(path, originalCommit);
        }

        void acknowledgeMove(String commit, Set<String> paths) {
            requireTracking(paths);
            paths.forEach(path -> baselines.put(path, commit));
            baseCommit = commit;
            move = null;
        }

        void requireTracking(Collection<String> paths) {
            long additional = paths.stream()
                    .distinct()
                    .filter(path -> !baselines.containsKey(path))
                    .count();
            if (baselines.size() + additional > 16384) {
                throw new IllegalArgumentException("session baseline capacity exhausted");
            }
        }
    }

    SyncPlan prepareSync(AuthPrincipal actor, WorkspaceId workspace, State state, String path, Optional<String> local) {
        auth.authorize(actor, workspace, Capability.READ_PRIVATE);
        if (state.uncertain || state.move != null) {
            throw new IllegalArgumentException("recover the pending write before synchronizing");
        }
        state.requireTracking(List.of(path));
        String previous = state.baseline(path);
        var original = reader.getFile(actor, workspace, Optional.of(previous), path);
        var remote = reader.getFile(actor, workspace, Optional.empty(), path);
        if ((!original.expectedAbsence() && original.source().isEmpty())
                || (!remote.expectedAbsence() && remote.source().isEmpty())
                || remote.commit().isEmpty()) {
            throw new IllegalArgumentException("synchronization requires a text path and an existing remote commit");
        }
        var merged = TextReconciliation.merge(original.source(), local, remote.source());
        return new SyncPlan(
                path,
                state.baseCommit,
                previous,
                remote.commit().orElseThrow(),
                local.map(value -> DocumentRevision.sha256(value.getBytes(StandardCharsets.UTF_8))
                        .value()
                        .substring(7)),
                merged.content(),
                merged.conflicted());
    }

    RepositoryFile baselineFile(AuthPrincipal actor, WorkspaceId workspace, State state, String path) {
        return reader.getFile(actor, workspace, Optional.of(state.baseline(path)), path);
    }

    void acknowledgeSync(State state, SyncPlan plan) {
        if (state.uncertain
                || !state.baseCommit.equals(plan.previousCommit())
                || !state.baseline(plan.path()).equals(plan.previousPathCommit())) {
            throw new IllegalStateException("session baseline changed during synchronization");
        }
        state.requireTracking(List.of(plan.path()));
        State proposed = state.copy();
        proposed.baselines.put(plan.path(), plan.remoteCommit());
        proposed.baseCommit = plan.remoteCommit();
        state.install(proposed);
    }

    record SyncPlan(
            String path,
            String previousCommit,
            String previousPathCommit,
            String remoteCommit,
            Optional<String> expectedLocalSha256,
            Optional<String> content,
            boolean conflicted) {}
}
