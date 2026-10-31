package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositoryMovePlan;
import io.github.core607.poketto.content.RepositoryMoveRequest;
import io.github.core607.poketto.content.RepositoryMoveService;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Application move planning preserves local index edits while the domain service owns remote repair and authority. */
final class WorkingCopyMoves {
    private final AuthorizedRepositoryReader reader;
    private final RepositoryMoveService service;

    WorkingCopyMoves(AuthorizedRepositoryReader reader, RepositoryMoveService service) {
        this.reader = reader;
        this.service = service;
    }

    RepositoryMoveService service() {
        return service;
    }

    /** Shares domain link repair and local-index preservation without creating a worker operation payload. */
    RepositoryMovePlan localPlan(
            AuthPrincipal actor,
            WorkspaceId workspace,
            SelectedFileSaves.State state,
            String source,
            String destination,
            Optional<String> localIndex) {
        if (state.uncertain || state.move != null || state.sync != null) {
            throw new IllegalArgumentException("recover the pending write first");
        }
        var request = new RepositoryMoveRequest(state.baseCommit, source, destination);
        var plan = service.plan(actor, workspace, request);
        if (!plan.workspace().equals(workspace) || !plan.request().equals(request)) {
            throw new IllegalStateException("move plan identity differs");
        }
        var originals = new LinkedHashMap<>(plan.originals());
        var replacements = new LinkedHashMap<>(plan.replacements());
        RepositoryMediaIndex before = baseline(actor, workspace, request.baseCommit());
        RepositoryMediaIndex local = parse(localIndex);
        requireSelectionUnchanged(before, local, source, destination);
        Set<String> gitTargets = gitTargets(plan);
        if (replacements.containsKey(RepositoryMediaIndex.PATH)) {
            var merged = merge(before, local, RepositoryMediaIndex.parse(replacements.get(RepositoryMediaIndex.PATH)));
            merged.requireNoGitCollisions(gitTargets);
            byte[] localBytes = localIndex.orElseThrow().getBytes(StandardCharsets.UTF_8);
            originals.put(
                    RepositoryMediaIndex.PATH,
                    new RepositoryMovePlan.Original(SandcrabProtocol.sha256(localBytes), localBytes.length, false));
            replacements.put(RepositoryMediaIndex.PATH, merged.encode());
        } else {
            local.requireNoGitCollisions(gitTargets);
        }
        var result = new RepositoryMovePlan(
                workspace, request, originals, plan.relocations(), replacements, plan.writeSha256());
        state.requireTracking(affected(result));
        return result;
    }

    static Set<String> affected(RepositoryMovePlan plan) {
        var affected = new HashSet<>(plan.originals().keySet());
        affected.addAll(plan.relocations().values());
        affected.addAll(plan.replacements().keySet());
        return Set.copyOf(affected);
    }

    // The media index baseline is the base commit's own; a read that answered another commit is refused.
    private RepositoryMediaIndex baseline(AuthPrincipal actor, WorkspaceId workspace, String baseCommit) {
        var file = reader.getFile(actor, workspace, Optional.of(baseCommit), RepositoryMediaIndex.PATH);
        if (!file.commit().equals(Optional.of(baseCommit))
                || (!file.expectedAbsence() && file.source().isEmpty())) {
            throw new IllegalArgumentException("move index baseline is unavailable");
        }
        return parse(file.source());
    }

    private static RepositoryMediaIndex parse(Optional<String> source) {
        return source.map(value -> RepositoryMediaIndex.parse(value.getBytes(StandardCharsets.UTF_8)))
                .orElseGet(RepositoryMediaIndex::empty);
    }

    // A local media mapping under either side of the move must equal the saved baseline: the move
    // would otherwise carry or drop an unsaved change silently.
    private static void requireSelectionUnchanged(
            RepositoryMediaIndex before, RepositoryMediaIndex local, String source, String destination) {
        var paths = new HashSet<>(before.files().keySet());
        paths.addAll(local.files().keySet());
        for (String path : paths) {
            if ((inside(path, source) || inside(path, destination))
                    && !Objects.equals(before.files().get(path), local.files().get(path))) {
                throw new IllegalArgumentException("selected local media mappings have unsaved changes");
            }
        }
    }

    private static Set<String> gitTargets(RepositoryMovePlan plan) {
        var targets = new HashSet<String>();
        plan.originals().forEach((path, original) -> {
            if (!original.optional()) {
                targets.add(plan.relocations().getOrDefault(path, path));
            }
        });
        return targets;
    }

    // The plan's index changes are applied on top of the local index; a mapping the plan changes
    // must still stand at its baseline locally.
    private static RepositoryMediaIndex merge(
            RepositoryMediaIndex before, RepositoryMediaIndex local, RepositoryMediaIndex after) {
        var merged = new LinkedHashMap<>(local.files());
        var changed = new HashSet<>(before.files().keySet());
        changed.addAll(after.files().keySet());
        for (String path : changed) {
            if (!Objects.equals(before.files().get(path), after.files().get(path))) {
                if (!Objects.equals(before.files().get(path), local.files().get(path))) {
                    throw new IllegalArgumentException("selected local media mapping changed");
                }
                if (after.files().containsKey(path)) {
                    merged.put(path, after.files().get(path));
                } else {
                    merged.remove(path);
                }
            }
        }
        return new RepositoryMediaIndex(merged);
    }

    private static boolean inside(String path, String parent) {
        return path.equals(parent) || path.startsWith(parent + "/");
    }
}
