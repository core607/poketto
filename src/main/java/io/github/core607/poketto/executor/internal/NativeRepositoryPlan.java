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
        boolean prepared) {
    enum Kind {
        SYNC,
        MOVE
    }

    NativeRepositoryPlan {
        Objects.requireNonNull(id, "plan identity must be present");
        Objects.requireNonNull(kind, "plan kind must be present");
        baseCommit = ProtocolValues.hex(baseCommit, 40, "plan base commit");
        remoteCommit = ProtocolValues.hex(remoteCommit, 40, "plan remote commit");
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
        files.forEach((path, file) -> file.remote().requirePath(path));
        accepted.values().forEach(NativeRepositoryPlan::identity);
        ProtocolValues.require(!prepared || staged.size() == paths.size(), "prepared plan", "must stage every path");
        ProtocolValues.require((kind == Kind.MOVE) == (move != null), "move plan", "must carry its remote request");
        if (move != null) {
            ProtocolValues.require(move.baseCommit().equals(baseCommit), "move base", "must match its preparation");
        }
    }

    NativeRepositoryPlan withFile(String path, File file) {
        var next = new LinkedHashMap<>(files);
        ProtocolValues.require(!next.containsKey(path), "prepared file", "cannot replace retained originals");
        next.put(path, file);
        return new NativeRepositoryPlan(id, kind, baseCommit, remoteCommit, move, paths, next, staged, accepted, false);
    }

    NativeRepositoryPlan staged(String path) {
        var next = new HashSet<>(staged);
        next.add(path);
        return new NativeRepositoryPlan(id, kind, baseCommit, remoteCommit, move, paths, files, next, accepted, false);
    }

    NativeRepositoryPlan ready() {
        return new NativeRepositoryPlan(id, kind, baseCommit, remoteCommit, move, paths, files, staged, accepted, true);
    }

    NativeRepositoryPlan accept(Map<String, String> expected) {
        ProtocolValues.require(
                prepared && files.keySet().containsAll(expected.keySet()),
                "accepted paths",
                "must belong to a completed preparation");
        expected.values().forEach(NativeRepositoryPlan::identity);
        var next = new LinkedHashMap<>(accepted);
        next.putAll(expected);
        return new NativeRepositoryPlan(id, kind, baseCommit, remoteCommit, move, paths, files, staged, next, true);
    }

    boolean complete() {
        return prepared && accepted.size() == files.size();
    }

    Set<String> retainedDigests() {
        var digests = new HashSet<String>();
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
    record File(NativePlanFile original, NativePlanFile candidate, RetainedFileBaseline remote, boolean conflict) {
        File {
            Objects.requireNonNull(remote, "prepared remote baseline must be present");
        }

        String originalIdentity() {
            return original == null ? "absent" : original.sha256();
        }

        String candidateIdentity() {
            return candidate == null ? "absent" : candidate.sha256();
        }
    }
}
