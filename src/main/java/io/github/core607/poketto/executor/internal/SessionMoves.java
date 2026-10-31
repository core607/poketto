package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.RepositoryMovePlan;
import io.github.core607.poketto.content.RepositoryMoveService;
import io.github.core607.poketto.content.RepositoryPatchResult;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
import io.github.core607.poketto.content.RepositoryWriteAttempt;
import io.github.core607.poketto.workspace.WorkspaceId;
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
    private final WorkingCopyMoves planning;

    SessionMoves(AuthorizedRepositoryReader reader, RepositoryMoveService service) {
        this.reader = reader;
        this.service = service;
        planning = new WorkingCopyMoves(reader, service);
    }

    PendingAccountMove prepare(
            AuthPrincipal actor,
            WorkspaceId workspace,
            SelectedFileSaves.State state,
            String source,
            String destination,
            Optional<String> localIndex) {
        RepositoryMovePlan plan = planning.localPlan(actor, workspace, state, source, destination, localIndex);
        Set<String> affected = WorkingCopyMoves.affected(plan);
        byte[] payload = payload(source, destination, plan.originals(), plan.relocations(), plan.replacements());
        return new PendingAccountMove(plan.request(), payload, affected);
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

    BridgeReplies.Reply commit(
            AuthPrincipal actor, WorkspaceId workspace, SelectedFileSaves.State state, PendingAccountMove pending) {
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
        PendingAccountMove pending = state.move;
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
            PendingAccountMove pending) {
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
        PendingAccountMove pending =
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
        PendingAccountMove pending =
                Objects.requireNonNull(state.move, "move must be present before skipping installation");
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

    static BridgeReplies.Reply pendingResult(PendingAccountMove pending, String code) {
        return BridgeReplies.failedWith(
                code,
                movePending(pending),
                "Run poketto recover to finish installation. If local changes prevent it, poketto recover --skip-local keeps those files and releases the confirmed move for per-file synchronization.");
    }

    /** The move as {@code poketto status} reports it, without the reply that usually wraps it. */
    static BridgeReplies.MovePending movePending(PendingAccountMove pending) {
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
}
