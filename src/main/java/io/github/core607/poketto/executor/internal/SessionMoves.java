package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositoryMovePlan;
import io.github.core607.poketto.content.RepositoryMoveRequest;
import io.github.core607.poketto.content.RepositoryMoveService;
import io.github.core607.poketto.content.RepositoryPatchResult;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
import io.github.core607.poketto.content.RepositoryWriteAttempt;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/** Serial session owner for a retained move; remote acknowledgement precedes local installation. */
final class SessionMoves {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AuthorizedRepositoryReader reader;
    private final RepositoryMoveService service;

    SessionMoves(AuthorizedRepositoryReader reader, RepositoryMoveService service) {
        this.reader = reader;
        this.service = service;
    }

    Pending prepare(
            AuthPrincipal actor,
            WorkspaceId workspace,
            SelectedFileSaves.State state,
            String source,
            String destination,
            Optional<String> localIndex) {
        if (state.uncertain || state.move != null)
            throw new IllegalArgumentException("recover the pending write first");
        var request = new RepositoryMoveRequest(state.baseCommit, source, destination);
        var plan = service.plan(actor, workspace, request);
        if (!plan.workspace().equals(workspace) || !plan.request().equals(request))
            throw new IllegalStateException("move plan identity differs");
        var originals = new LinkedHashMap<>(plan.originals());
        var replacements = new LinkedHashMap<>(plan.replacements());
        var beforeFile = reader.getFile(actor, workspace, Optional.of(request.baseCommit()), RepositoryMediaIndex.PATH);
        if (!beforeFile.commit().equals(Optional.of(request.baseCommit()))
                || (!beforeFile.expectedAbsence() && beforeFile.source().isEmpty()))
            throw new IllegalArgumentException("move index baseline is unavailable");
        var before = beforeFile
                .source()
                .map(value -> RepositoryMediaIndex.parse(value.getBytes(StandardCharsets.UTF_8)))
                .orElseGet(RepositoryMediaIndex::empty);
        var local = localIndex
                .map(value -> RepositoryMediaIndex.parse(value.getBytes(StandardCharsets.UTF_8)))
                .orElseGet(RepositoryMediaIndex::empty);
        var paths = new HashSet<>(before.files().keySet());
        paths.addAll(local.files().keySet());
        for (String path : paths) {
            if ((inside(path, source) || inside(path, destination))
                    && !Objects.equals(before.files().get(path), local.files().get(path)))
                throw new IllegalArgumentException("selected local media mappings have unsaved changes");
        }
        var gitTargets = new HashSet<String>();
        plan.originals().forEach((path, original) -> {
            if (!original.optional()) gitTargets.add(plan.relocations().getOrDefault(path, path));
        });
        if (replacements.containsKey(RepositoryMediaIndex.PATH)) {
            var after = RepositoryMediaIndex.parse(replacements.get(RepositoryMediaIndex.PATH));
            var merged = new LinkedHashMap<>(local.files());
            var changed = new HashSet<>(before.files().keySet());
            changed.addAll(after.files().keySet());
            for (String path : changed) {
                if (!Objects.equals(before.files().get(path), after.files().get(path))) {
                    if (!Objects.equals(before.files().get(path), local.files().get(path)))
                        throw new IllegalArgumentException("selected local media mapping changed");
                    if (after.files().containsKey(path))
                        merged.put(path, after.files().get(path));
                    else merged.remove(path);
                }
            }
            var mergedIndex = new RepositoryMediaIndex(merged);
            mergedIndex.requireNoGitCollisions(gitTargets);
            byte[] localBytes = localIndex.orElseThrow().getBytes(StandardCharsets.UTF_8);
            originals.put(
                    RepositoryMediaIndex.PATH,
                    new RepositoryMovePlan.Original(hash(localBytes), localBytes.length, false));
            replacements.put(RepositoryMediaIndex.PATH, mergedIndex.encode());
        } else {
            local.requireNoGitCollisions(gitTargets);
        }
        var affected = new HashSet<>(originals.keySet());
        affected.addAll(plan.relocations().values());
        affected.addAll(replacements.keySet());
        state.requireTracking(affected);
        byte[] payload = JSON.writeValueAsBytes(Map.of(
                "operationId",
                java.util.UUID.randomUUID().toString(),
                "source",
                source,
                "destination",
                destination,
                "originals",
                originals,
                "relocations",
                plan.relocations(),
                "replacements",
                replacements));
        if (payload.length > 64 * 1024 * 1024)
            throw new IllegalArgumentException("move plan exceeds transfer capacity");
        return new Pending(request, payload, Set.copyOf(affected));
    }

    private static boolean inside(String path, String parent) {
        return path.equals(parent) || path.startsWith(parent + "/");
    }

    private static String hash(byte[] bytes) {
        return DocumentRevision.sha256(bytes).value().substring(7);
    }

    Map<String, ?> commit(AuthPrincipal actor, WorkspaceId workspace, SelectedFileSaves.State state, Pending pending) {
        if (state.uncertain || state.move != null || !state.baseCommit.equals(pending.request.baseCommit()))
            throw new IllegalArgumentException("move baseline changed");
        state.move = pending;
        return write(actor, workspace, state, false);
    }

    Map<String, ?> recover(AuthPrincipal actor, WorkspaceId workspace, SelectedFileSaves.State state) {
        if (state.move == null) throw new IllegalArgumentException("no pending move");
        if (state.move.result != null) return pendingResult(state.move, "LOCAL_MOVE_PENDING");
        if (state.move.attempt == null) return pendingResult(state.move, "WRITE_OUTCOME_UNKNOWN");
        return write(actor, workspace, state, true);
    }

    private Map<String, ?> write(
            AuthPrincipal actor, WorkspaceId workspace, SelectedFileSaves.State state, boolean recovery) {
        Pending pending = state.move;
        try {
            pending.result = recovery
                    ? service.recover(actor, workspace, pending.request, pending.attempt)
                    : service.move(actor, workspace, pending.request);
            return pendingResult(pending, "LOCAL_MOVE_PENDING");
        } catch (RepositoryWriteAmbiguousException unknown) {
            if (pending.attempt == null) pending.attempt = unknown.attempt().orElse(null);
            return pendingResult(pending, "WRITE_OUTCOME_UNKNOWN");
        } catch (RepositoryConflictException conflict) {
            state.move = null;
            return Map.of("ok", false, "code", "REPOSITORY_CONFLICT");
        } catch (io.github.core607.poketto.auth.AuthException
                | io.github.core607.poketto.content.ContentRepositoryException
                | IllegalArgumentException failure) {
            if (!recovery) state.move = null;
            throw failure;
        }
    }

    Map<String, ?> installed(SelectedFileSaves.State state) {
        Pending pending = Objects.requireNonNull(state.move);
        if (pending.result == null) throw new IllegalStateException("move is not acknowledged");
        var result = pending.result;
        state.acknowledgeMove(result.commit(), pending.paths);
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
                        "worktreeUpdated",
                        true,
                        "source",
                        pending.request.source(),
                        "destination",
                        pending.request.destination()));
        return state.lastSave;
    }

    Map<String, ?> skipLocal(SelectedFileSaves.State state) {
        Pending pending = Objects.requireNonNull(state.move);
        var result = Objects.requireNonNull(pending.result, "move must be confirmed before skipping installation");
        // Local bytes did not advance: retain every per-file baseline to guard later saves.
        state.baseCommit = result.commit();
        state.move = null;
        state.lastSave = Map.of(
                "ok",
                true,
                "result",
                Map.of(
                        "commit",
                        result.commit(),
                        "committed",
                        result.committed(),
                        "worktreeUpdated",
                        false,
                        "localInstallationSkipped",
                        true),
                "message",
                "Local files are unchanged. Use poketto sync on affected text and index paths before saving them.");
        return state.lastSave;
    }

    static Map<String, ?> pendingResult(Pending pending, String code) {
        var result = new LinkedHashMap<String, Object>();
        result.put("committed", pending.result != null && pending.result.committed());
        result.put("commit", pending.result == null ? null : pending.result.commit());
        result.put("worktreeUpdated", false);
        result.put("source", pending.request.source());
        result.put("destination", pending.request.destination());
        return Map.of(
                "ok",
                false,
                "code",
                code,
                "result",
                result,
                "message",
                "Run poketto recover to finish installation. If local changes prevent it, poketto recover --skip-local keeps those files and releases the confirmed move for per-file synchronization.");
    }

    static final class Pending {
        final RepositoryMoveRequest request;
        final byte[] payload;
        final Set<String> paths;
        RepositoryWriteAttempt attempt;
        RepositoryPatchResult result;

        Pending(RepositoryMoveRequest request, byte[] payload, Set<String> paths) {
            this.request = request;
            this.payload = payload;
            this.paths = paths;
        }
    }
}
