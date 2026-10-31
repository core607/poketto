package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.RepositoryMoveRequest;
import io.github.core607.poketto.content.RepositoryPaths;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** One durable preparation, including original bytes and explicitly accepted local identities. */
record NativeRepositoryPlan(
        UUID id,
        Kind kind,
        String baseCommit,
        String remoteCommit,
        RepositoryMoveRequest move,
        List<String> paths,
        Map<String, File> files,
        Set<String> staged,
        Map<String, String> accepted,
        boolean prepared,
        NativeMoveState moveState) {
    enum Kind {
        SYNC,
        MOVE
    }

    boolean releasable() {
        return moveState == null || (!moveState.recoveryRequired() && (moveState.result() == null || complete()));
    }

    NativeRepositoryPlan {
        Objects.requireNonNull(id, "plan identity must be present");
        Objects.requireNonNull(kind, "plan kind must be present");
        baseCommit = ProtocolValues.hex(baseCommit, 40, "plan base commit");
        if (kind == Kind.SYNC) {
            remoteCommit = ProtocolValues.hex(remoteCommit, 40, "plan remote commit");
        } else {
            ProtocolValues.require(remoteCommit == null, "move preparation", "has no acknowledged remote commit");
        }
        paths = List.copyOf(paths);
        ProtocolValues.inRange(paths.size(), 0, WorkspaceSyncInputs.MAX_PATHS, "plan paths");
        var selected = new HashSet<>(paths);
        ProtocolValues.require(selected.size() == paths.size(), "plan paths", "must be unique");
        paths.forEach(RepositoryPaths::validate);
        files = Map.copyOf(files);
        staged = Set.copyOf(staged);
        accepted = Map.copyOf(accepted);
        ProtocolValues.require(selected.containsAll(files.keySet()), "plan files", "must belong to its path inventory");
        ProtocolValues.require(files.keySet().containsAll(accepted.keySet()), "accepted paths", "must be prepared");
        ProtocolValues.require(files.keySet().containsAll(staged), "staged paths", "must be prepared");
        Kind operation = kind;
        files.forEach((path, file) -> {
            if (operation == Kind.SYNC) {
                Objects.requireNonNull(file.remote(), "sync remote baseline must be present")
                        .requirePath(path);
            } else {
                ProtocolValues.require(
                        file.remote() == null, "move file", "has no remote baseline before acknowledgement");
            }
        });
        accepted.values().forEach(NativeRepositoryPlan::identity);
        ProtocolValues.require(!prepared || staged.size() == paths.size(), "prepared plan", "must stage every path");
        ProtocolValues.require((kind == Kind.MOVE) == (move != null), "move plan", "must carry its remote request");
        ProtocolValues.require((kind == Kind.MOVE) == (moveState != null), "move state", "must belong to a move plan");
        for (String path : staged) {
            ProtocolValues.require(files.get(path).prepared(), "staged candidate", "must be prepared");
        }
        if (move != null) {
            ProtocolValues.require(move.baseCommit().equals(baseCommit), "move base", "must match its preparation");
            ProtocolValues.require(
                    selected.containsAll(moveState.baselined()), "move baseline paths", "must belong to the plan");
            requireMoveAcceptance(files, accepted, prepared, paths.size(), moveState);
        }
    }

    private static void requireMoveAcceptance(
            Map<String, File> files, Map<String, String> accepted, boolean prepared, int paths, NativeMoveState move) {
        accepted.forEach((path, digest) -> ProtocolValues.require(
                prepared && files.get(path).candidateIdentity().equals(digest),
                "move acceptance",
                "must match its prepared candidate"));
        ProtocolValues.require(
                !move.started() || (prepared && accepted.size() == paths),
                "move intent",
                "requires explicit acceptance of every path");
    }

    NativeRepositoryPlan withFile(String path, File file) {
        var next = new LinkedHashMap<>(files);
        ProtocolValues.require(!next.containsKey(path), "prepared file", "cannot replace retained originals");
        next.put(path, file);
        return new NativeRepositoryPlan(
                id, kind, baseCommit, remoteCommit, move, paths, next, staged, accepted, false, moveState);
    }

    NativeRepositoryPlan withCandidate(String path, NativePlanFile candidate) {
        File captured = files.get(path);
        ProtocolValues.require(
                captured != null && !captured.prepared(), "move candidate", "requires an unprepared original capture");
        var next = new LinkedHashMap<>(files);
        next.put(path, new File(captured.original(), candidate, null, false));
        return new NativeRepositoryPlan(
                id, kind, baseCommit, remoteCommit, move, paths, next, staged, accepted, false, moveState);
    }

    NativeRepositoryPlan withMoveState(NativeMoveState state) {
        ProtocolValues.require(kind == Kind.MOVE, "move state", "requires a move plan");
        return new NativeRepositoryPlan(
                id, kind, baseCommit, remoteCommit, move, paths, files, staged, accepted, prepared, state);
    }

    NativeRepositoryPlan staged(String path) {
        var next = new HashSet<>(staged);
        next.add(path);
        return new NativeRepositoryPlan(
                id, kind, baseCommit, remoteCommit, move, paths, files, next, accepted, false, moveState);
    }

    NativeRepositoryPlan ready() {
        return new NativeRepositoryPlan(
                id, kind, baseCommit, remoteCommit, move, paths, files, staged, accepted, true, moveState);
    }

    NativeRepositoryPlan accept(Map<String, String> expected) {
        ProtocolValues.require(
                prepared && files.keySet().containsAll(expected.keySet()),
                "accepted paths",
                "must belong to a completed preparation");
        expected.values().forEach(NativeRepositoryPlan::identity);
        var next = new LinkedHashMap<>(accepted);
        next.putAll(expected);
        return new NativeRepositoryPlan(
                id, kind, baseCommit, remoteCommit, move, paths, files, staged, next, true, moveState);
    }

    boolean complete() {
        return localComplete()
                && (kind == Kind.SYNC
                        || moveState.result() != null && moveState.baselined().size() == paths.size());
    }

    boolean localComplete() {
        return prepared && accepted.size() == files.size();
    }

    Set<String> retainedDigests() {
        var digests = new HashSet<String>();
        if (moveState != null && moveState.definition() != null) {
            digests.add(moveState.definition().sha256());
        }
        for (File file : files.values()) {
            if (file.original() != null) {
                digests.add(file.original().sha256());
            }
            if (file.candidate() != null) {
                digests.add(file.candidate().sha256());
            }
        }
        return Set.copyOf(digests);
    }

    static String identity(String value) {
        return "absent".equals(value) ? value : ProtocolValues.hex(value, 64, "accepted local digest");
    }

    /** A null file identity means absence; conflict candidates never overwrite the user's working path. */
    record File(
            NativePlanFile original,
            NativePlanFile candidate,
            RetainedFileBaseline remote,
            boolean conflict,
            boolean prepared) {
        File(NativePlanFile original, NativePlanFile candidate, RetainedFileBaseline remote, boolean conflict) {
            this(original, candidate, remote, conflict, true);
        }

        File {
            ProtocolValues.require(prepared || candidate == null, "unprepared file", "must not claim a candidate");
        }

        String originalIdentity() {
            return original == null ? "absent" : original.sha256();
        }

        String candidateIdentity() {
            return candidate == null ? "absent" : candidate.sha256();
        }
    }
}
