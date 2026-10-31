package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.RepositoryMovePlan;
import io.github.core607.poketto.content.RepositoryMoveRequest;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Reuses the domain move and index plan, retaining it before staging any local candidate. */
final class NativeMovePreparation {
    private static final int DEFINITION_BYTES = RepositoryMovePlan.MAX_REPLACEMENT_BYTES * 2;
    private final SelectedFileSaves saves;
    private final NativePlanIo files;

    NativeMovePreparation(SelectedFileSaves saves, NativePlanIo files) {
        this.saves = saves;
        this.files = files;
    }

    BridgeReplies.Reply prepare(
            AuthPrincipal actor,
            AccountCommand held,
            NativeRepositoryLease lease,
            BridgeArguments.Move query,
            BooleanSupplier outOfTime) {
        SelectedFileSaves.State state = held.state();
        var workspace = new WorkspaceId(held.owner().workspaceId());
        var request = new RepositoryMoveRequest(state.baseCommit, query.source(), query.destination());
        ProtocolValues.require(
                !state.uncertain && state.move == null && state.sync == null,
                "move preparation",
                "requires reconciliation of the pending write");
        RepositoryMovePlan definition = definition(actor, workspace, held, lease, state, request);
        NativeRepositoryPlan plan = state.nativePlan;
        if (plan.moveState().started()) {
            return BridgeReplies.failedWith("RECOVER_MOVE_FIRST", NativePlanReplies.summary(plan));
        }
        boolean advanced = false;
        for (String path : plan.paths()) {
            if (state.nativePlan.files().containsKey(path)) {
                continue;
            }
            if (advanced && outOfTime.getAsBoolean()) {
                return NativePlanReplies.progress(state.nativePlan);
            }
            NativePlanIo.Captured original = files.capture(held, lease, plan.id(), path);
            String problem = precondition(definition, path, original.file());
            if (problem != null) {
                return BridgeReplies.failedWith(
                        problem,
                        new LocalConflict(plan.id().toString(), path),
                        "Working files are unchanged. Inspect or recover retained originals, then release this plan before saving or synchronizing affected local edits.");
            }
            state.retainPlan(state.nativePlan.withFile(
                    path, new NativeRepositoryPlan.File(original.file(), null, null, false, false)));
            advanced = true;
        }
        return candidates(held, lease, state, definition, outOfTime);
    }

    private RepositoryMovePlan definition(
            AuthPrincipal actor,
            WorkspaceId workspace,
            AccountCommand held,
            NativeRepositoryLease lease,
            SelectedFileSaves.State state,
            RepositoryMoveRequest request) {
        NativeRepositoryPlan plan = state.nativePlan;
        if (plan != null) {
            ProtocolValues.require(
                    plan.kind() == NativeRepositoryPlan.Kind.MOVE && plan.move().equals(request),
                    "move preparation",
                    "must resume the same retained request");
            if (plan.moveState().definition() != null) {
                return retained(held, lease, plan);
            }
        }
        RepositoryMovePlan definition = saves.moves()
                .localPlan(actor, workspace, state, request.source(), request.destination(), files.index(lease));
        if (plan == null) {
            plan = new NativeRepositoryPlan(
                    UUID.randomUUID(),
                    NativeRepositoryPlan.Kind.MOVE,
                    state.baseCommit,
                    null,
                    request,
                    WorkingCopyMoves.affected(definition).stream().sorted().toList(),
                    Map.of(),
                    Set.of(),
                    Map.of(),
                    false,
                    NativeMoveState.empty());
            state.retainPlan(plan);
        }
        requireDefinition(plan, workspace, definition);
        byte[] bytes = SandcrabProtocol.write(definition);
        ProtocolValues.inRange(bytes.length, 1, DEFINITION_BYTES, "move definition bytes");
        NativePlanFile retained = files.retain(held, lease, plan.id(), bytes);
        state.retainPlan(plan.withMoveState(new NativeMoveState(retained, false, null, null, false, Set.of())));
        return definition;
    }

    RepositoryMovePlan retained(AccountCommand held, NativeRepositoryLease lease, NativeRepositoryPlan plan) {
        RepositoryMovePlan retained = SandcrabProtocol.read(
                files.read(held, lease, plan.id(), plan.moveState().definition(), DEFINITION_BYTES),
                RepositoryMovePlan.class);
        requireDefinition(plan, new WorkspaceId(held.owner().workspaceId()), retained);
        return retained;
    }

    private static void requireDefinition(
            NativeRepositoryPlan plan, WorkspaceId workspace, RepositoryMovePlan definition) {
        ProtocolValues.require(
                definition.workspace().equals(workspace)
                        && definition.request().equals(plan.move())
                        && WorkingCopyMoves.affected(definition).equals(Set.copyOf(plan.paths())),
                "move definition",
                "must match its retained owner, request and path inventory");
    }

    private BridgeReplies.Reply candidates(
            AccountCommand held,
            NativeRepositoryLease lease,
            SelectedFileSaves.State state,
            RepositoryMovePlan definition,
            BooleanSupplier outOfTime) {
        boolean advanced = false;
        Map<String, byte[]> replacements = definition.replacements();
        for (String path : state.nativePlan.paths()) {
            if (state.nativePlan.staged().contains(path)) {
                continue;
            }
            if (advanced && outOfTime.getAsBoolean()) {
                return NativePlanReplies.progress(state.nativePlan);
            }
            if (!state.nativePlan.files().get(path).prepared()) {
                NativePlanFile candidate = candidate(held, lease, state.nativePlan, definition, replacements, path);
                state.retainPlan(state.nativePlan.withCandidate(path, candidate));
            }
            files.stage(held, lease, state.nativePlan, path);
            state.retainPlan(state.nativePlan.staged(path));
            advanced = true;
        }
        state.retainPlan(state.nativePlan.ready());
        return NativePlanReplies.progress(state.nativePlan);
    }

    private NativePlanFile candidate(
            AccountCommand held,
            NativeRepositoryLease lease,
            NativeRepositoryPlan plan,
            RepositoryMovePlan definition,
            Map<String, byte[]> replacements,
            String path) {
        byte[] replacement = replacements.get(path);
        if (replacement != null) {
            return files.retain(held, lease, plan.id(), replacement);
        }
        for (Map.Entry<String, String> relocation : definition.relocations().entrySet()) {
            if (relocation.getValue().equals(path)) {
                return plan.files().get(relocation.getKey()).original();
            }
        }
        return definition.relocations().containsKey(path)
                ? null
                : plan.files().get(path).original();
    }

    private static String precondition(RepositoryMovePlan plan, String path, NativePlanFile observed) {
        RepositoryMovePlan.Original expected = plan.originals().get(path);
        if (expected == null) {
            return observed == null ? null : "LOCAL_MOVE_TARGET_EXISTS";
        }
        if (observed == null) {
            return expected.optional() ? null : "LOCAL_MOVE_DIRTY";
        }
        return observed.bytes() == expected.bytes() && observed.sha256().equals(expected.sha256())
                ? null
                : "LOCAL_MOVE_DIRTY";
    }

    record LocalConflict(String planId, String path) {}
}
