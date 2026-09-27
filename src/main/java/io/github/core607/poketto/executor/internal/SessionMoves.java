package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.SessionWorker.hash;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.ContentRepositoryException;
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
import java.util.UUID;
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
                    new RepositoryMovePlan.Original(hash(localBytes), localBytes.length, false));
            replacements.put(RepositoryMediaIndex.PATH, merged.encode());
        } else {
            local.requireNoGitCollisions(gitTargets);
        }
        var affected = new HashSet<>(originals.keySet());
        affected.addAll(plan.relocations().values());
        affected.addAll(replacements.keySet());
        state.requireTracking(affected);
        byte[] payload = payload(source, destination, originals, plan.relocations(), replacements);
        return new Pending(request, payload, Set.copyOf(affected));
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

    private static byte[] payload(
            String source,
            String destination,
            Map<String, RepositoryMovePlan.Original> originals,
            Map<String, String> relocations,
            Map<String, byte[]> replacements) {
        byte[] payload = JSON.writeValueAsBytes(new TransferredPlan(
                UUID.randomUUID().toString(),
                source,
                destination,
                Map.copyOf(originals),
                relocations,
                Map.copyOf(replacements)));
        if (payload.length > 64 * 1024 * 1024) {
            throw new IllegalArgumentException("move plan exceeds transfer capacity");
        }
        return payload;
    }

    private static boolean inside(String path, String parent) {
        return path.equals(parent) || path.startsWith(parent + "/");
    }

    BridgeReplies.Reply commit(
            AuthPrincipal actor, WorkspaceId workspace, SelectedFileSaves.State state, Pending pending) {
        if (state.uncertain || state.move != null || !state.baseCommit.equals(pending.request.baseCommit())) {
            throw new IllegalArgumentException("move baseline changed");
        }
        SelectedFileSaves.State proposed = state.copy();
        proposed.move = RetainedSaveState.Move.capture(pending).restore();
        state.install(proposed);
        return write(actor, workspace, state, false);
    }

    BridgeReplies.Reply recover(AuthPrincipal actor, WorkspaceId workspace, SelectedFileSaves.State state) {
        if (state.move == null) {
            throw new IllegalArgumentException("no pending move");
        }
        if (state.move.result != null) {
            return pendingResult(state.move, "LOCAL_MOVE_PENDING");
        }
        if (state.move.attempt == null) {
            clearMove(state);
            return BridgeReplies.succeeded(new BridgeReplies.Recovery(false));
        }
        return write(actor, workspace, state, true);
    }

    private BridgeReplies.Reply write(
            AuthPrincipal actor, WorkspaceId workspace, SelectedFileSaves.State state, boolean recovery) {
        Pending pending = state.move;
        RepositoryPatchResult result;
        try {
            result = writeRemote(actor, workspace, state, recovery, pending);
        } catch (RepositoryWriteAmbiguousException unknown) {
            SelectedFileSaves.State proposed = state.copy();
            if (proposed.move.attempt == null) {
                proposed.move.attempt = unknown.attempt().orElse(null);
            }
            state.install(proposed);
            return pendingResult(state.move, "WRITE_OUTCOME_UNKNOWN");
        } catch (RepositoryConflictException conflict) {
            clearMove(state);
            return BridgeReplies.failed("REPOSITORY_CONFLICT");
        } catch (AuthException | ContentRepositoryException | IllegalArgumentException failure) {
            if (!recovery) {
                clearMove(state);
            }
            throw failure;
        }
        SelectedFileSaves.State proposed = state.copy();
        proposed.move.result = result;
        proposed.move.fileBaselines = fileBaselines(actor, workspace, result.commit(), pending.paths);
        state.install(proposed);
        return pendingResult(state.move, "LOCAL_MOVE_PENDING");
    }

    private Map<String, RetainedFileBaseline> fileBaselines(
            AuthPrincipal actor, WorkspaceId workspace, String commit, Set<String> paths) {
        var result = new LinkedHashMap<String, RetainedFileBaseline>();
        for (String path : paths) {
            var file = reader.getFile(actor, workspace, Optional.of(commit), path);
            if (!file.commit().equals(Optional.of(commit))) {
                throw new ContentRepositoryException("acknowledged move baseline is unavailable");
            }
            result.put(path, RetainedFileBaseline.capture(workspace, commit, path, file));
        }
        return Map.copyOf(result);
    }

    private RepositoryPatchResult writeRemote(
            AuthPrincipal actor,
            WorkspaceId workspace,
            SelectedFileSaves.State state,
            boolean recovery,
            Pending pending) {
        return recovery
                ? service.recover(
                        actor, workspace, pending.request, pending.attempt, attempt -> retainAttempt(state, attempt))
                : service.move(actor, workspace, pending.request, attempt -> retainAttempt(state, attempt));
    }

    private static void retainAttempt(SelectedFileSaves.State state, RepositoryWriteAttempt attempt) {
        SelectedFileSaves.State proposed = state.copy();
        proposed.move.attempt = attempt;
        state.install(proposed);
    }

    private static void clearMove(SelectedFileSaves.State state) {
        SelectedFileSaves.State proposed = state.copy();
        proposed.move = null;
        state.install(proposed);
    }

    BridgeReplies.Reply installed(SelectedFileSaves.State state) {
        Pending pending =
                Objects.requireNonNull(state.move, "move must be present before installation acknowledgement");
        if (pending.result == null) {
            throw new IllegalStateException("move is not acknowledged");
        }
        RepositoryPatchResult result = pending.result;
        SelectedFileSaves.State proposed = state.copy();
        proposed.acknowledgeMove(result.commit(), pending.paths, pending.fileBaselines);
        BridgeReplies.Reply reply = BridgeReplies.succeeded(new BridgeReplies.MoveInstalled(
                result.commit(),
                result.committed(),
                result.snapshotUpdated(),
                true,
                pending.request.source(),
                pending.request.destination()));
        proposed.lastSave = reply;
        state.install(proposed);
        return reply;
    }

    BridgeReplies.Reply skipLocal(SelectedFileSaves.State state) {
        Pending pending = Objects.requireNonNull(state.move, "move must be present before skipping installation");
        RepositoryPatchResult result =
                Objects.requireNonNull(pending.result, "move must be confirmed before skipping installation");
        // Local bytes did not advance: retain every per-file baseline to guard later saves.
        SelectedFileSaves.State proposed = state.copy();
        proposed.baseCommit = result.commit();
        proposed.move = null;
        BridgeReplies.Reply reply = BridgeReplies.succeededWithMessage(
                new BridgeReplies.MoveSkipped(result.commit(), result.committed(), false, true),
                "Local files are unchanged. Use poketto sync to reconcile the workspace before saving affected paths.");
        proposed.lastSave = reply;
        state.install(proposed);
        return reply;
    }

    static BridgeReplies.Reply pendingResult(Pending pending, String code) {
        return BridgeReplies.failedWith(
                code,
                movePending(pending),
                "Run poketto recover to finish installation. If local changes prevent it, poketto recover --skip-local keeps those files and releases the confirmed move for per-file synchronization.");
    }

    /** The move as {@code poketto status} reports it, without the reply that usually wraps it. */
    static BridgeReplies.MovePending movePending(Pending pending) {
        return new BridgeReplies.MovePending(
                pending.result != null && pending.result.committed(),
                pending.result == null ? null : pending.result.commit(),
                false,
                pending.request.source(),
                pending.request.destination());
    }

    /**
     * The move as it crosses to the worker, streamed in bounded chunks rather than sent as an
     * operation payload. The worker reads these field names directly, so renaming one here renames
     * it on the wire.
     */
    record TransferredPlan(
            String operationId,
            String source,
            String destination,
            Map<String, RepositoryMovePlan.Original> originals,
            Map<String, String> relocations,
            // Jackson renders each replacement as base64, which is the form the worker decodes.
            Map<String, byte[]> replacements) {}

    static final class Pending {
        final RepositoryMoveRequest request;
        final byte[] payload;
        final Set<String> paths;
        RepositoryWriteAttempt attempt;
        RepositoryPatchResult result;
        Map<String, RetainedFileBaseline> fileBaselines = Map.of();

        Pending(RepositoryMoveRequest request, byte[] payload, Set<String> paths) {
            this.request = request;
            this.payload = payload;
            this.paths = paths;
        }
    }
}
