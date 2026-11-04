package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.content.RepositoryMovePlan;
import io.github.core607.poketto.content.RepositoryPatchResult;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
import io.github.core607.poketto.content.RepositoryWriteAttempt;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Explicit path verification gates one saved domain move; uncertain writes keep their exact candidate. */
final class NativeMoves {
    private final SelectedFileSaves saves;
    private final NativePlanIo files;
    private final NativeMovePreparation preparation;

    NativeMoves(SelectedFileSaves saves, NativePlanIo files) {
        this.saves = saves;
        this.files = files;
        preparation = new NativeMovePreparation(saves, files);
    }

    BridgeReplies.Reply prepare(
            AuthPrincipal actor,
            AccountCommand held,
            NativeRepositoryLease lease,
            BridgeArguments.Move query,
            BooleanSupplier outOfTime) {
        return preparation.prepare(actor, held, lease, query, outOfTime);
    }

    BridgeReplies.Reply accept(
            AuthPrincipal actor,
            AccountCommand held,
            NativeRepositoryLease lease,
            BridgeArguments.PlanAccept query,
            BooleanSupplier outOfTime) {
        SelectedFileSaves.State state = held.state();
        NativeRepositoryPlan plan = require(state, query.planId());
        ProtocolValues.require(plan.prepared(), "move acceptance", "requires completed preparation");
        if (plan.moveState().conflicted()) {
            return reply(state, "REPOSITORY_CONFLICT");
        }
        if (plan.moveState().recoveryRequired()) {
            return reply(state, "RECOVER_MOVE_FIRST");
        }
        Map<String, String> verified = new LinkedHashMap<>();
        for (BridgeArguments.PlanExpected expected : query.files()) {
            NativeRepositoryPlan.File file = plan.files().get(expected.path());
            ProtocolValues.require(
                    file != null && file.candidateIdentity().equals(expected.sha256()),
                    "accepted move contents",
                    "must match the retained candidate or deletion");
            if (!files.observed(lease, expected.path()).equals(expected.sha256())) {
                return reply(state, "LOCAL_FILE_CHANGED");
            }
            verified.put(expected.path(), expected.sha256());
        }
        lease.authorize();
        state.retainPlan(plan.accept(verified));
        if (!state.nativePlan.localComplete()) {
            return reply(state, "MOVE_ACCEPTANCE_PENDING");
        }
        if (state.nativePlan.moveState().result() == null) {
            if (outOfTime.getAsBoolean()) {
                return reply(state, "MOVE_ACCEPTANCE_PENDING");
            }
            RepositoryMovePlan definition = preparation.retained(held, lease, state.nativePlan);
            state.retainPlan(
                    state.nativePlan.withMoveState(state.nativePlan.moveState().starting()));
            BridgeReplies.Reply failed = write(actor, held, lease, state, definition, false);
            if (failed != null) {
                return failed;
            }
        }
        return baselines(actor, held, lease, state, outOfTime);
    }

    BridgeReplies.Reply recover(
            AuthPrincipal actor,
            AccountCommand held,
            NativeRepositoryLease lease,
            String id,
            BooleanSupplier outOfTime) {
        SelectedFileSaves.State state = held.state();
        NativeRepositoryPlan plan = require(state, id);
        if (!plan.prepared()) {
            return NativePlanReplies.progress(plan);
        }
        NativeMoveState outcome = plan.moveState();
        if (outcome.conflicted()) {
            return reply(state, "REPOSITORY_CONFLICT");
        }
        if (outcome.result() == null) {
            if (outcome.attempt() == null) {
                state.retainPlan(plan.withMoveState(
                        new NativeMoveState(outcome.definition(), false, null, null, false, Set.of())));
                return reply(state, "MOVE_NOT_SUBMITTED");
            }
            if (outOfTime.getAsBoolean()) {
                return reply(state, "RECOVER_MOVE_FIRST");
            }
            BridgeReplies.Reply failed =
                    write(actor, held, lease, state, preparation.retained(held, lease, plan), true);
            if (failed != null) {
                return failed;
            }
        }
        return baselines(actor, held, lease, state, outOfTime);
    }

    private BridgeReplies.Reply write(
            AuthPrincipal actor,
            AccountCommand held,
            NativeRepositoryLease lease,
            SelectedFileSaves.State state,
            RepositoryMovePlan definition,
            boolean recovery) {
        var workspace = new WorkspaceId(held.owner().workspaceId());
        NativeMoveState outcome = state.nativePlan.moveState();
        lease.authorize();
        RepositoryPatchResult result;
        try {
            result = recovery
                    ? saves.moves()
                            .service()
                            .recoverPrepared(
                                    actor,
                                    workspace,
                                    definition.request(),
                                    definition.writeSha256(),
                                    outcome.attempt(),
                                    attempt -> retainAttempt(state, attempt))
                    : saves.moves()
                            .service()
                            .movePrepared(
                                    actor,
                                    workspace,
                                    definition.request(),
                                    definition.writeSha256(),
                                    attempt -> retainAttempt(state, attempt));
        } catch (RepositoryWriteAmbiguousException unknown) {
            if (state.nativePlan.moveState().attempt() == null
                    && unknown.attempt().isPresent()) {
                retainAttempt(state, unknown.attempt().orElseThrow());
            }
            return reply(state, "WRITE_OUTCOME_UNKNOWN");
        } catch (RepositoryConflictException conflict) {
            state.retainPlan(
                    state.nativePlan.withMoveState(state.nativePlan.moveState().refused()));
            return reply(state, "REPOSITORY_CONFLICT");
        }
        state.retainPlan(
                state.nativePlan.withMoveState(state.nativePlan.moveState().confirmed(result)));
        return null;
    }

    private static void retainAttempt(SelectedFileSaves.State state, RepositoryWriteAttempt attempt) {
        state.retainPlan(
                state.nativePlan.withMoveState(state.nativePlan.moveState().withAttempt(attempt)));
    }

    private BridgeReplies.Reply baselines(
            AuthPrincipal actor,
            AccountCommand held,
            NativeRepositoryLease lease,
            SelectedFileSaves.State state,
            BooleanSupplier outOfTime) {
        NativeRepositoryPlan plan = state.nativePlan;
        var workspace = new WorkspaceId(held.owner().workspaceId());
        String commit = plan.moveState().result().commit();
        Map<String, RetainedFileBaseline> versions = new LinkedHashMap<>();
        for (String path : plan.paths()) {
            if (plan.moveState().baselined().contains(path)) {
                continue;
            }
            if (!versions.isEmpty() && outOfTime.getAsBoolean()) {
                break;
            }
            lease.authorize();
            RepositoryFile file = saves.repository().getFile(actor, workspace, Optional.of(commit), path);
            if (!file.commit().equals(Optional.of(commit))) {
                throw new ContentRepositoryException("Acknowledged move baseline is unavailable");
            }
            versions.put(path, RetainedFileBaseline.capture(workspace, commit, path, file));
        }
        lease.authorize();
        state.acknowledgeMovePlanFiles(versions);
        return reply(state, state.nativePlan.complete() ? null : "MOVE_BASELINES_PENDING");
    }

    private static NativeRepositoryPlan require(SelectedFileSaves.State state, String id) {
        NativeRepositoryPlan plan = state.nativePlan;
        ProtocolValues.require(
                plan != null && plan.id().toString().equals(id) && plan.kind() == NativeRepositoryPlan.Kind.MOVE,
                "move identity",
                "must match its retained preparation");
        return plan;
    }

    private static BridgeReplies.Reply reply(SelectedFileSaves.State state, String code) {
        NativeMoveState move = state.nativePlan.moveState();
        var result = new Outcome(
                NativePlanReplies.summary(state.nativePlan),
                move.result() != null,
                move.result() != null && move.result().committed(),
                state.nativePlan.localComplete(),
                state.nativePlan.accepted().size(),
                "Each path was checked at its own explicit acceptance; this is not a simultaneous worktree snapshot.");
        String message =
                switch (code == null ? "COMPLETE" : code) {
                    case "WRITE_OUTCOME_UNKNOWN", "RECOVER_MOVE_FIRST" ->
                        "Do not repeat the move. Run poketto recover to reconcile the retained attempt; working files remain as edited.";
                    case "REPOSITORY_CONFLICT" ->
                        "The remote move conflicts with its saved plan. Working files remain as edited; recover originals before releasing or replanning.";
                    case "LOCAL_FILE_CHANGED" ->
                        "A selected path differs from the explicit expectation; this acceptance was not recorded.";
                    case "MOVE_NOT_SUBMITTED" ->
                        "The retained journal contains no submitted remote candidate. Recheck your files and explicitly accept the plan to request the move.";
                    case "MOVE_BASELINES_PENDING" ->
                        "The remote move is confirmed. Run poketto recover to retain the remaining baselines; no working files were installed.";
                    case "MOVE_ACCEPTANCE_PENDING" ->
                        "Path acceptance is retained. Accept the remaining paths, or repeat accept after a time-limited confirmation; no remote move has been confirmed.";
                    default ->
                        "The remote move and host baselines are confirmed. Working files remain as edited; release the plan when its backups are no longer needed.";
                };
        return code == null
                ? BridgeReplies.succeededWithMessage(result, message)
                : BridgeReplies.failedWith(code, result, message);
    }

    record Outcome(
            NativePlanReplies.Summary plan,
            boolean remoteConfirmed,
            boolean remoteCommitted,
            boolean localVerified,
            int verifiedPaths,
            String verificationScope) {}
}
