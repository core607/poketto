package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.RepositoryBaselineLimits;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

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

    AuthorizedRepositoryReader repository() {
        return reader;
    }

    Optional<String> currentCommit(AuthPrincipal actor, WorkspaceId workspace) {
        return reader.currentCommit(actor, workspace);
    }

    void visitOriginal(
            AuthPrincipal actor,
            WorkspaceId workspace,
            String commit,
            RepositoryBaselineLimits limits,
            Consumer<RepositoryFile> sink) {
        reader.visitBaseline(actor, workspace, commit, limits, sink);
    }

    WorkspaceSyncInputs prepareWorkspaceSync(AuthPrincipal actor, WorkspaceId workspace, State state) {
        auth.authorize(actor, workspace, Capability.READ_PRIVATE);
        if (state.uncertain || state.move != null) {
            throw new IllegalArgumentException("recover the pending write before synchronizing");
        }
        String remoteCommit = state.sync == null
                ? reader.currentCommit(actor, workspace)
                        .orElseThrow(() ->
                                new IllegalArgumentException("synchronization requires an existing remote commit"))
                : state.sync.commit();
        var limits = new RepositoryBaselineLimits(
                WorkspaceSyncInputs.MAX_PATHS, WorkspaceSyncInputs.MAX_TEXT_BYTES, Duration.ofSeconds(20));
        var inputs = new WorkspaceSyncInputs.Collector(state.baseCommit, remoteCommit);
        Consumer<RepositoryFile> baseline = file -> {
            RetainedFileBaseline advanced = state.fileBaselines.get(file.path());
            inputs.baseline(advanced == null ? file : advanced.file(workspace, file.path()));
        };
        if (state.originals == null) {
            reader.visitBaseline(actor, workspace, state.originalCommit, limits, baseline);
        } else {
            state.originals.visit(actor, workspace, state.originalCommit, baseline);
        }
        for (String path : state.fileBaselines.keySet()) {
            inputs.baseline(baselineFile(actor, workspace, state, path));
        }
        reader.visitBaseline(actor, workspace, remoteCommit, limits, inputs::remote);
        WorkspaceSyncInputs result = inputs.finish();
        return auth.withAuthorization(actor, workspace, Set.of(Capability.READ_PRIVATE), () -> result);
    }

    BridgeReplies.Reply save(
            AuthPrincipal actor, WorkspaceId workspace, State state, Map<String, String> writes, List<String> deletes) {
        auth.authorize(actor, workspace);
        if (state.sync != null) {
            return BridgeReplies.failed("RECOVER_SYNC_FIRST");
        }
        if (state.move != null) {
            return SessionMoves.pendingResult(state.move, "RECOVER_MOVE_FIRST");
        }
        if (state.uncertain) {
            return BridgeReplies.failed("WRITE_OUTCOME_UNKNOWN");
        }
        RepositoryPatch patch = selectedPatch(actor, workspace, state, writes, deletes);
        prepareRetainedWrite(state, patch);
        RepositoryPatchResult result;
        try {
            result = patches.apply(actor, workspace, patch, attempt -> retainAttempt(state, attempt));
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
        return completed(state, result, patch, false);
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
            var baseline = baselineFile(actor, workspace, state, path);
            if (!writableBaseline(baseline, expectedCommit)) {
                throw new InvalidSelectionException(InvalidSelectionException.Reason.NO_WRITABLE_BASELINE);
            }
            changes.add(new RepositoryTextChange(
                    path, baseline.expectedAbsence(), baseline.revision(), Optional.ofNullable(writes.get(path))));
        }
        return new RepositoryPatch(Optional.of(state.baseCommit), changes);
    }

    private static boolean writableBaseline(RepositoryFile baseline, String commit) {
        return baseline.commit().equals(Optional.of(commit))
                && (baseline.expectedAbsence()
                        || (baseline.source().isPresent() && baseline.revision().isPresent()));
    }

    private static void prepareRetainedWrite(State state, RepositoryPatch patch) {
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
        if (state.attempt.isEmpty()) {
            // A remote push cannot begin before its exact candidate was durably retained.
            State proposed = state.copy();
            proposed.uncertain = false;
            proposed.pending = null;
            return remember(state, proposed, BridgeReplies.succeeded(new BridgeReplies.Recovery(false)));
        }
        if (state.pending == null || state.attempt.isEmpty()) {
            return BridgeReplies.failed("WRITE_OUTCOME_UNKNOWN");
        }
        RepositoryPatchResult result;
        try {
            result = patches.recover(
                    actor,
                    workspace,
                    state.pending,
                    state.attempt.orElseThrow(),
                    attempt -> retainAttempt(state, attempt));
        } catch (RepositoryWriteAmbiguousException unknown) {
            // Retain the same original patch and commit even if the recovery reply is also lost.
            return remember(state, state.copy(), BridgeReplies.failed("WRITE_OUTCOME_UNKNOWN"));
        } catch (RepositoryConflictException conflict) {
            return conflict(
                    state, "Remote main diverged from the retained attempt; local edits and baseline are retained.");
        }
        return completed(state, result, state.pending, true);
    }

    private static BridgeReplies.Reply completed(
            State state, RepositoryPatchResult result, RepositoryPatch patch, boolean recovered) {
        List<String> paths =
                patch.changes().stream().map(RepositoryTextChange::path).toList();
        State proposed = state.copy();
        proposed.baseCommit = result.commit();
        // Only these paths adopt the new remote baseline; unselected local edits keep theirs.
        patch.changes()
                .forEach(change -> proposed.fileBaselines.put(
                        change.path(),
                        RetainedFileBaseline.saved(
                                result.commit(), change.content().orElse(null))));
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
        private final SaveStateJournal journal;
        private final OriginalFileLookup originals;
        private final Map<String, RetainedFileBaseline> fileBaselines = new HashMap<>();
        String baseCommit;
        boolean uncertain;
        RepositoryPatch pending;
        Optional<RepositoryWriteAttempt> attempt = Optional.empty();
        BridgeReplies.Recorded lastSave = new BridgeReplies.Absent();
        BridgeReplies.Recorded lastImport = new BridgeReplies.Absent();
        SessionMoves.Pending move;
        PendingWorkspaceSync sync;

        State(String baseCommit) {
            this(baseCommit, SaveStateJournal.NONE);
        }

        State(String baseCommit, SaveStateJournal journal) {
            this(baseCommit, journal, null);
        }

        private State(String baseCommit, SaveStateJournal journal, OriginalFileLookup originals) {
            this.originalCommit = baseCommit;
            this.baseCommit = baseCommit;
            this.journal = Objects.requireNonNull(journal, "save journal must be present");
            this.originals = originals;
        }

        State copy() {
            return restore(snapshot(), journal, originals);
        }

        void install(State proposed) {
            ProtocolValues.require(
                    originalCommit.equals(proposed.originalCommit), "save state", "must keep its original commit");
            journal.retain(proposed.snapshot());
            copyFields(proposed);
        }

        private void copyFields(State proposed) {
            baseCommit = proposed.baseCommit;
            fileBaselines.clear();
            fileBaselines.putAll(proposed.fileBaselines);
            uncertain = proposed.uncertain;
            pending = proposed.pending;
            attempt = proposed.attempt;
            lastSave = proposed.lastSave;
            lastImport = proposed.lastImport;
            move = proposed.move;
            sync = proposed.sync;
        }

        /** The disk record's redundant commit index is derived only at the serialization boundary. */
        private Map<String, String> fileCommits() {
            var commits = new HashMap<String, String>();
            fileBaselines.forEach((path, file) -> commits.put(path, file.commit()));
            return commits;
        }

        RetainedSaveState snapshot() {
            return new RetainedSaveState(
                    originalCommit,
                    baseCommit,
                    fileCommits(),
                    fileBaselines,
                    uncertain,
                    pending,
                    attempt.orElse(null),
                    RetainedSaveState.Move.capture(move),
                    BridgeReplies.RestoredReceipt.capture(lastSave),
                    BridgeReplies.RestoredReceipt.capture(lastImport),
                    sync);
        }

        static State restore(RetainedSaveState snapshot) {
            return restore(snapshot, SaveStateJournal.NONE);
        }

        static State restore(RetainedSaveState snapshot, SaveStateJournal journal) {
            return restore(snapshot, journal, null);
        }

        static State restore(RetainedSaveState snapshot, SaveStateJournal journal, OriginalFileLookup originals) {
            snapshot.requireRecoverable();
            var state = new State(snapshot.originalCommit(), journal, originals);
            state.baseCommit = snapshot.baseCommit();
            state.fileBaselines.putAll(snapshot.fileBaselines());
            state.uncertain = snapshot.uncertain();
            state.pending = snapshot.pending();
            state.attempt = Optional.ofNullable(snapshot.attempt());
            state.move = snapshot.move() == null ? null : snapshot.move().restore();
            state.lastSave = snapshot.lastSave();
            state.lastImport = snapshot.lastImport();
            state.sync = snapshot.sync();
            return state;
        }

        void retainSync(PendingWorkspaceSync pendingSync) {
            State proposed = copy();
            proposed.sync = pendingSync;
            install(proposed);
        }

        /** Stages acknowledged progress on a private state copy before its next durable installation. */
        void advanceSyncFile() {
            PendingWorkspaceSync pendingSync = Objects.requireNonNull(sync, "pending sync must be present");
            PendingWorkspaceSync.File file = Objects.requireNonNull(pendingSync.current(), "sync file must be present");
            requireTracking(List.of(file.path()));
            fileBaselines.put(file.path(), file.baseline());
            sync = pendingSync.advanced();
        }

        BridgeReplies.Reply finishSync(boolean skipped) {
            PendingWorkspaceSync pendingSync = Objects.requireNonNull(sync, "pending sync must be present");
            State proposed = copy();
            proposed.sync = null;
            if (!skipped) {
                proposed.baseCommit = pendingSync.commit();
            }
            var result = BridgeReplies.workspaceSyncResult(pendingSync, skipped);
            BridgeReplies.Reply reply = BridgeReplies.outcome(
                    skipped || pendingSync.conflicts().isEmpty(),
                    skipped ? "SYNC_RELEASED" : pendingSync.conflicts().isEmpty() ? "SYNCHRONIZED" : "MERGE_CONFLICT",
                    result);
            proposed.lastSave = reply;
            install(proposed);
            return reply;
        }

        void acknowledgeImport(BridgeReplies.ImportReceipt receipt) {
            State proposed = copy();
            proposed.lastImport = Objects.requireNonNull(receipt, "import receipt must be present");
            install(proposed);
        }

        String baseline(String path) {
            RetainedFileBaseline file = fileBaselines.get(path);
            return file == null ? originalCommit : file.commit();
        }

        void acknowledgeMove(String commit, Set<String> paths, Map<String, RetainedFileBaseline> retainedFiles) {
            requireTracking(paths);
            ProtocolValues.require(retainedFiles.keySet().equals(paths), "move baselines", "must cover affected paths");
            retainedFiles.forEach((path, file) -> {
                file.requirePath(path);
                ProtocolValues.require(
                        file.commit().equals(commit), "move baseline", "must match the acknowledged commit");
            });
            fileBaselines.putAll(retainedFiles);
            baseCommit = commit;
            move = null;
        }

        void requireTracking(Collection<String> paths) {
            long additional = paths.stream()
                    .distinct()
                    .filter(path -> !fileBaselines.containsKey(path))
                    .count();
            if (fileBaselines.size() + additional > 16384) {
                throw new IllegalArgumentException("session baseline capacity exhausted");
            }
        }
    }

    RepositoryFile baselineFile(AuthPrincipal actor, WorkspaceId workspace, State state, String path) {
        RetainedFileBaseline retained = state.fileBaselines.get(path);
        if (retained != null) {
            return auth.withAuthorization(
                    actor, workspace, Set.of(Capability.READ_PRIVATE), () -> retained.file(workspace, path));
        }
        if (state.originals != null) {
            if (!state.baseline(path).equals(state.originalCommit)) {
                throw new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE);
            }
            auth.authorize(actor, workspace, Capability.READ_PRIVATE);
            RepositoryFile file = state.originals.file(actor, workspace, state.originalCommit, path);
            return auth.withAuthorization(actor, workspace, Set.of(Capability.READ_PRIVATE), () -> file);
        }
        return reader.getFile(actor, workspace, Optional.of(state.baseline(path)), path);
    }
}
