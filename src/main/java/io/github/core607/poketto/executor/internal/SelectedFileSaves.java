package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
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
        try {
            var result = patches.apply(actor, workspace, new RepositoryPatch(Optional.of(state.baseCommit), changes));
            // Only these selected files changed in the new authoritative tree. All other local
            // edits retain their old authoritative contents as their next save preconditions.
            state.baseCommit = result.commit();
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
                            List.copyOf(paths)));
        } catch (RepositoryWriteAmbiguousException unknown) {
            state.uncertain = true;
            state.lastSave = Map.of(
                    "ok",
                    false,
                    "code",
                    "WRITE_OUTCOME_UNKNOWN",
                    "message",
                    "Read authoritative remote state before another save; local edits are retained.");
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

    static final class State {
        String baseCommit;
        boolean uncertain;
        Map<String, ?> lastSave = Map.of();

        State(String baseCommit) {
            this.baseCommit = baseCommit;
        }
    }
}
