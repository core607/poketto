package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryPatchResult;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
import io.github.core607.poketto.content.RepositoryWriteAttempt;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Uses only authoritative revisions; sandbox Git refs and indexes never supply write preconditions. */
final class SelectedFileSaves {
    private final AuthService auth;
    private final AuthorizedRepositoryReader reader;
    private final RepositoryPatchService patches;

    SelectedFileSaves(AuthService auth, AuthorizedRepositoryReader reader, RepositoryPatchService patches) {
        this.auth = auth;
        this.reader = reader;
        this.patches = patches;
    }

    Map<String, ?> save(
            AuthPrincipal actor, WorkspaceId workspace, State state, Map<String, String> writes, List<String> deletes) {
        auth.authorize(actor, workspace, Capability.WRITE_PRIVATE);
        if (state.uncertain) return Map.of("ok", false, "code", "WRITE_OUTCOME_UNKNOWN");
        var paths = new HashSet<>(writes.keySet());
        if (writes.size() + deletes.size() < 1
                || writes.size() + deletes.size() > RepositoryPatch.MAX_CHANGES
                || deletes.stream().anyMatch(path -> !paths.add(path)))
            throw new IllegalArgumentException("Select distinct files within the save bound");
        var changes = new ArrayList<RepositoryTextChange>();
        for (String path : paths) {
            var baseline = reader.getFile(actor, workspace, Optional.of(state.baseCommit), path);
            if (!baseline.commit().equals(Optional.of(state.baseCommit))
                    || (!baseline.expectedAbsence() && baseline.revision().isEmpty()))
                throw new IllegalArgumentException("Selected path has no writable text baseline");
            changes.add(new RepositoryTextChange(
                    path, baseline.expectedAbsence(), baseline.revision(), Optional.ofNullable(writes.get(path))));
        }
        RepositoryPatch patch = new RepositoryPatch(Optional.of(state.baseCommit), changes);
        try {
            var result = patches.apply(actor, workspace, patch);
            // Only these selected files changed in the new authoritative tree. All other local
            // edits retain their old authoritative contents as their next save preconditions.
            completed(state, result, List.copyOf(paths), false);
        } catch (RepositoryWriteAmbiguousException unknown) {
            state.pending = patch;
            state.attempt = unknown.attempt();
            state.uncertain = true;
            state.lastSave = Map.of(
                    "ok",
                    false,
                    "code",
                    "WRITE_OUTCOME_UNKNOWN",
                    "message",
                    "Run poketto recover to reconcile the retained commit before another save; local edits are retained.");
        } catch (RepositoryConflictException conflict) {
            state.lastSave = Map.of(
                    "ok",
                    false,
                    "code",
                    "REPOSITORY_CONFLICT",
                    "message",
                    "Remote main changed; local edits and the host baseline are retained.");
        }
        return state.lastSave;
    }

    Map<String, ?> recover(AuthPrincipal actor, WorkspaceId workspace, State state) {
        auth.authorize(actor, workspace, Capability.READ_PRIVATE, Capability.WRITE_PRIVATE);
        if (!state.uncertain) return Map.of("ok", true, "result", Map.of("recoveryNeeded", false));
        if (state.pending == null || state.attempt.isEmpty())
            return Map.of("ok", false, "code", "WRITE_OUTCOME_UNKNOWN");
        try {
            var result = patches.recover(actor, workspace, state.pending, state.attempt.orElseThrow());
            completed(
                    state,
                    result,
                    state.pending.changes().stream()
                            .map(RepositoryTextChange::path)
                            .toList(),
                    true);
        } catch (RepositoryWriteAmbiguousException unknown) {
            // Retain the same original patch and commit even if the recovery reply is also lost.
            state.lastSave = Map.of("ok", false, "code", "WRITE_OUTCOME_UNKNOWN");
        } catch (RepositoryConflictException conflict) {
            state.uncertain = false;
            state.pending = null;
            state.attempt = Optional.empty();
            state.lastSave = Map.of(
                    "ok",
                    false,
                    "code",
                    "REPOSITORY_CONFLICT",
                    "message",
                    "Remote main diverged from the retained attempt; local edits and baseline are retained.");
        }
        return state.lastSave;
    }

    private static void completed(State state, RepositoryPatchResult result, List<String> paths, boolean recovered) {
        state.baseCommit = result.commit();
        state.uncertain = false;
        state.pending = null;
        state.attempt = Optional.empty();
        state.lastSave = Map.of(
                "ok",
                true,
                "result",
                Map.of(
                        "commit",
                        result.commit(),
                        "committed",
                        result.committed(),
                        "snapshotUpdated",
                        result.snapshotUpdated(),
                        "paths",
                        paths,
                        "recovered",
                        recovered));
    }

    static final class State {
        String baseCommit;
        boolean uncertain;
        RepositoryPatch pending;
        Optional<RepositoryWriteAttempt> attempt = Optional.empty();
        Map<String, ?> lastSave = Map.of();

        State(String baseCommit) {
            this.baseCommit = baseCommit;
        }
    }
}
