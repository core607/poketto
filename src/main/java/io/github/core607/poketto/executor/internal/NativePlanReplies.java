package io.github.core607.poketto.executor.internal;

import java.util.ArrayList;
import java.util.List;

/** Pages describe immutable plan identities without embedding original or candidate contents. */
final class NativePlanReplies {
    private NativePlanReplies() {}

    record Summary(
            String planId,
            NativeRepositoryPlan.Kind kind,
            String baseCommit,
            String remoteCommit,
            int totalPaths,
            int capturedPaths,
            int preparedPaths,
            int stagedPaths,
            int acceptedPaths,
            boolean prepared,
            boolean complete,
            MoveStatus move) {}

    record MoveStatus(
            boolean recoveryRequired,
            boolean conflicted,
            boolean remoteConfirmed,
            boolean remoteCommitted,
            String attemptCommit,
            int baselinedPaths) {}

    record Entry(
            String path,
            boolean prepared,
            NativePlanFile original,
            NativePlanFile candidate,
            String originalPath,
            String candidatePath,
            boolean conflict,
            String acceptedSha256) {}

    record Page(Summary plan, int offset, Integer nextOffset, List<Entry> items) {}

    static Summary summary(NativeRepositoryPlan plan) {
        return plan == null
                ? null
                : new Summary(
                        plan.id().toString(),
                        plan.kind(),
                        plan.baseCommit(),
                        plan.moveState() != null && plan.moveState().result() != null
                                ? plan.moveState().result().commit()
                                : plan.remoteCommit(),
                        plan.paths().size(),
                        plan.files().size(),
                        (int) plan.files().values().stream()
                                .filter(NativeRepositoryPlan.File::prepared)
                                .count(),
                        plan.staged().size(),
                        plan.accepted().size(),
                        plan.prepared(),
                        plan.complete(),
                        move(plan.moveState()));
    }

    private static MoveStatus move(NativeMoveState state) {
        return state == null
                ? null
                : new MoveStatus(
                        state.recoveryRequired(),
                        state.conflicted(),
                        state.result() != null,
                        state.result() != null && state.result().committed(),
                        state.attempt() == null ? null : state.attempt().commit(),
                        state.baselined().size());
    }

    static BridgeReplies.Reply progress(NativeRepositoryPlan plan) {
        return BridgeReplies.succeededWithMessage(
                summary(plan),
                plan.prepared()
                        ? "Preparation is retained. Inspect plan pages and candidates, edit your working files, then accept explicit path hashes. No remote write was made."
                        : "Preparation progress is retained. Run prepare again to finish staging; your working files are unchanged.");
    }

    static BridgeReplies.Reply page(NativeRepositoryPlan plan, int offset, int limit) {
        ProtocolValues.inRange(offset, 0, plan.paths().size(), "plan page offset");
        var items = new ArrayList<Entry>();
        int next = offset;
        while (next < plan.paths().size() && items.size() < limit) {
            items.add(entry(plan, plan.paths().get(next)));
            if (SandcrabProtocol.write(page(plan, offset, next + 1, items)).length > MediaListing.MAX_PAGE_BYTES) {
                items.removeLast();
                if (items.isEmpty()) {
                    throw new IllegalArgumentException("Prepared path exceeds the page byte bound");
                }
                break;
            }
            next++;
        }
        return page(plan, offset, next, items);
    }

    private static BridgeReplies.Reply page(NativeRepositoryPlan plan, int offset, int next, List<Entry> items) {
        return BridgeReplies.succeeded(
                new Page(summary(plan), offset, next == plan.paths().size() ? null : next, List.copyOf(items)));
    }

    private static Entry entry(NativeRepositoryPlan plan, String path) {
        NativeRepositoryPlan.File file = plan.files().get(path);
        return file == null
                ? new Entry(path, false, null, null, null, null, false, null)
                : new Entry(
                        path,
                        file.prepared(),
                        file.original(),
                        file.candidate(),
                        NativePlanIo.destination(plan.id(), "original", file.original()),
                        NativePlanIo.destination(plan.id(), "candidate", file.candidate()),
                        file.conflict(),
                        plan.accepted().get(path));
    }
}
