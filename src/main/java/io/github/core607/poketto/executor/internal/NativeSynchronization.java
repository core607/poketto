package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Prepares ordinary candidate files; only explicit, verified acceptance advances host baselines. */
final class NativeSynchronization {
    private final SelectedFileSaves saves;
    private final WorkspaceSynchronization merge;
    private final NativePlanIo files;

    NativeSynchronization(SelectedFileSaves saves, NativePlanIo files) {
        this.saves = saves;
        this.merge = new WorkspaceSynchronization(saves);
        this.files = files;
    }

    BridgeReplies.Reply prepare(
            AuthPrincipal actor, AccountCommand held, NativeRepositoryLease lease, BooleanSupplier outOfTime) {
        SelectedFileSaves.State state = held.state();
        requireAvailable(state);
        var workspace = new WorkspaceId(held.owner().workspaceId());
        WorkspaceSyncInputs inputs = saves.prepareWorkspaceSync(actor, workspace, state);
        if (state.nativePlan == null) {
            state.retainPlan(new NativeRepositoryPlan(
                    UUID.randomUUID(),
                    NativeRepositoryPlan.Kind.SYNC,
                    state.baseCommit,
                    inputs.remoteCommit(),
                    null,
                    WorkspaceSynchronization.ordered(inputs).stream()
                            .filter(path -> WorkspaceSynchronization.leaf(
                                            inputs.baseline().get(path))
                                    || WorkspaceSynchronization.leaf(
                                            inputs.remote().get(path)))
                            .filter(path -> merge.requiresReconciliation(actor, workspace, state, inputs, path))
                            .toList(),
                    Map.of(),
                    Set.of(),
                    Map.of(),
                    false));
        }
        NativeRepositoryPlan plan = state.nativePlan;
        boolean advanced = false;
        for (String path : plan.paths()) {
            if (state.nativePlan.staged().contains(path)) {
                continue;
            }
            if (advanced && outOfTime.getAsBoolean()) {
                return NativePlanReplies.progress(state.nativePlan);
            }
            if (!state.nativePlan.files().containsKey(path)) {
                prepareFile(actor, workspace, held, lease, state, inputs, path);
            }
            files.stage(held, lease, state.nativePlan, path);
            state.retainPlan(state.nativePlan.staged(path));
            advanced = true;
        }
        state.retainPlan(state.nativePlan.ready());
        return NativePlanReplies.progress(state.nativePlan);
    }

    private void prepareFile(
            AuthPrincipal actor,
            WorkspaceId workspace,
            AccountCommand held,
            NativeRepositoryLease lease,
            SelectedFileSaves.State state,
            WorkspaceSyncInputs inputs,
            String path) {
        UUID plan = state.nativePlan.id();
        NativePlanIo.Captured original = files.capture(held, lease, plan, path);
        PendingWorkspaceSync.File selected =
                merge.plan(actor, workspace, state, inputs, path, new WorkspaceSynchronization.Files() {
                    @Override
                    public WorkspaceSynchronization.Local read(String selectedPath) {
                        ProtocolValues.require(
                                path.equals(selectedPath), "sync capture", "must match its planned path");
                        return original.local();
                    }

                    @Override
                    public boolean install(PendingWorkspaceSync.File ignored) {
                        throw new IllegalStateException("Preparation never installs a working file");
                    }
                });
        NativePlanFile candidate = files.candidate(actor, workspace, held, lease, plan, selected, original);
        lease.authorize();
        state.retainPlan(state.nativePlan.withFile(
                path,
                new NativeRepositoryPlan.File(original.file(), candidate, selected.baseline(), selected.conflict())));
    }

    BridgeReplies.Reply accept(AccountCommand held, NativeRepositoryLease lease, BridgeArguments.PlanAccept query) {
        SelectedFileSaves.State state = held.state();
        NativeRepositoryPlan plan = requirePlan(state, query.planId());
        ProtocolValues.require(plan.prepared(), "synchronization acceptance", "requires completed preparation");
        Map<String, String> verified = new LinkedHashMap<>();
        for (BridgeArguments.PlanExpected expected : query.files()) {
            ProtocolValues.require(
                    plan.files().containsKey(expected.path()), "accepted path", "must belong to the plan");
            if (!expected.sha256().equals(files.observed(lease, expected.path()))) {
                return BridgeReplies.failed(
                        "LOCAL_FILE_CHANGED",
                        "The selected contents differ from the explicit expectation; no paths in this acceptance were acknowledged.");
            }
            verified.put(expected.path(), expected.sha256());
        }
        lease.authorize();
        state.acceptPlanFiles(verified);
        return BridgeReplies.succeededWithMessage(
                NativePlanReplies.summary(state.nativePlan),
                "Accepted path baselines now use the retained remote revision. Your edited files are unchanged; unaccepted paths keep their baselines.");
    }

    static NativeRepositoryPlan requirePlan(SelectedFileSaves.State state, String id) {
        NativeRepositoryPlan plan = state.nativePlan;
        ProtocolValues.require(
                plan != null && plan.id().toString().equals(id),
                "plan identity",
                "must match the retained preparation");
        ProtocolValues.require(
                plan.kind() == NativeRepositoryPlan.Kind.SYNC, "synchronization plan", "must not be a move");
        return plan;
    }

    private static void requireAvailable(SelectedFileSaves.State state) {
        ProtocolValues.require(
                !state.uncertain && state.move == null && state.sync == null,
                "synchronization preparation",
                "requires reconciliation of the pending write");
        ProtocolValues.require(
                state.nativePlan == null || state.nativePlan.kind() == NativeRepositoryPlan.Kind.SYNC,
                "synchronization preparation",
                "must not replace a move plan");
    }
}
