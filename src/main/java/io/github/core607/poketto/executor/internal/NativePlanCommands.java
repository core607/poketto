package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.JsonNode;

/** Explicit prepare, inspect, verify and release operations over the locked account's retained plan. */
final class NativePlanCommands {
    private final NativePlanIo files;
    private final NativeSynchronization sync;
    private final NativeMoves moves;

    NativePlanCommands(NativeRepositoryFiles files, SelectedFileSaves saves) {
        this.files = new NativePlanIo(files, saves);
        sync = new NativeSynchronization(saves, this.files);
        moves = new NativeMoves(saves, this.files);
    }

    BridgeReplies.Reply command(
            AuthPrincipal principal,
            AccountCommand held,
            NativeRepositoryLease lease,
            String operation,
            JsonNode arguments,
            BooleanSupplier outOfTime) {
        if (!held.owner().fullRead()) {
            return BridgeReplies.failed("READ_ONLY_SCOPE");
        }
        lease.authorize();
        try {
            return operation(principal, held, lease, operation, arguments, outOfTime);
        } catch (NativePlanIo.UnsupportedPath unsupported) {
            return BridgeReplies.failedWith(
                    "LOCAL_PATH_TYPE",
                    new Unsupported(unsupported.path()),
                    "The selected path is not a regular file or confirmed absence. Preserve or relocate the conflicting local entry, then retry; no working files were replaced.");
        } catch (RetainedCopyException capacity) {
            if (capacity.reason() != RetainedCopyException.Reason.LIMIT) {
                throw capacity;
            }
            return BridgeReplies.failedWith(
                    "PLAN_CAPACITY",
                    NativePlanReplies.summary(held.record().state().nativePlan()),
                    "Plan storage capacity is exhausted. Working files and acknowledged preparation remain; inspect or explicitly release the plan.");
        }
    }

    private BridgeReplies.Reply operation(
            AuthPrincipal principal,
            AccountCommand held,
            NativeRepositoryLease lease,
            String operation,
            JsonNode arguments,
            BooleanSupplier outOfTime) {
        return switch (operation) {
            case "sync_prepare" -> {
                BridgeArguments.sync(arguments);
                yield sync.prepare(principal, held, lease, outOfTime);
            }
            case "sync_accept" -> sync.accept(held, lease, BridgeArguments.planAccept(arguments));
            case "move_prepare" -> moves.prepare(principal, held, lease, BridgeArguments.move(arguments), outOfTime);
            case "move_accept" ->
                moves.accept(principal, held, lease, BridgeArguments.planAccept(arguments), outOfTime);
            case "move_recover" ->
                moves.recover(
                        principal,
                        held,
                        lease,
                        BridgeArguments.planIdentity(arguments).planId(),
                        outOfTime);
            case "plan_show" -> show(held, BridgeArguments.planPage(arguments));
            case "plan_file" -> materialize(held, lease, BridgeArguments.planFile(arguments));
            case "plan_release" -> release(held, BridgeArguments.planIdentity(arguments));
            default -> throw new IllegalArgumentException("Unknown retained plan operation");
        };
    }

    private static BridgeReplies.Reply show(AccountCommand held, BridgeArguments.PlanPage query) {
        return NativePlanReplies.page(require(held.state(), query.planId()), query.offset(), query.limit());
    }

    private BridgeReplies.Reply materialize(
            AccountCommand held, NativeRepositoryLease lease, BridgeArguments.PlanFile query) {
        NativeRepositoryPlan plan = require(held.state(), query.planId());
        NativeRepositoryPlan.File selected = plan.files().get(query.path());
        ProtocolValues.require(selected != null, "plan file", "must be prepared before materialization");
        ProtocolValues.require(
                query.version().equals("original") || selected.prepared(),
                "candidate file",
                "must finish preparation before materialization");
        NativePlanFile file = query.version().equals("original") ? selected.original() : selected.candidate();
        if (file == null) {
            return BridgeReplies.failed(
                    "PLAN_FILE_ABSENT", "This plan version records absence; no file was materialized.");
        }
        files.materialize(held, lease, plan.id(), file, query.output());
        return BridgeReplies.succeeded(
                new Materialized(plan.id().toString(), query.path(), query.version(), query.output(), file));
    }

    private static BridgeReplies.Reply release(AccountCommand held, BridgeArguments.PlanIdentity query) {
        SelectedFileSaves.State state = held.state();
        NativeRepositoryPlan plan = require(state, query.planId());
        if (!plan.releasable()) {
            return BridgeReplies.failedWith(
                    "RECOVER_MOVE_FIRST",
                    NativePlanReplies.summary(plan),
                    "Reconcile the retained remote attempt and baselines before releasing its recovery data.");
        }
        state.retainPlan(null);
        return BridgeReplies.succeededWithMessage(
                new Released(plan.id().toString(), true),
                "Application plan backups were released. Accepted baselines and working files are unchanged; local staging files remain.");
    }

    private static NativeRepositoryPlan require(SelectedFileSaves.State state, String id) {
        NativeRepositoryPlan plan = state.nativePlan;
        ProtocolValues.require(
                plan != null && plan.id().toString().equals(id),
                "plan identity",
                "must match the retained preparation");
        return plan;
    }

    record Materialized(String planId, String path, String version, String output, NativePlanFile file) {}

    record Released(String planId, boolean released) {}

    record Unsupported(String path) {}
}
