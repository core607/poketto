package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.RepositoryPaths;
import io.github.core607.poketto.content.RepositorySyncEntry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** One fixed remote revision and the exact next local installation, retained before changing files. */
record PendingWorkspaceSync(String commit, List<String> paths, int next, List<String> conflicts, File current) {
    PendingWorkspaceSync {
        commit = ProtocolValues.hex(commit, 40, "synchronization commit");
        paths = List.copyOf(paths);
        ProtocolValues.require(paths.size() <= WorkspaceSyncInputs.MAX_PATHS, "sync paths", "exceeds its bound");
        var selected = new HashSet<>(paths);
        ProtocolValues.require(selected.size() == paths.size(), "sync paths", "must be unique");
        paths.forEach(RepositoryPaths::validate);
        ProtocolValues.inRange(next, 0, paths.size(), "next synchronization path");
        conflicts = List.copyOf(conflicts);
        ProtocolValues.require(selected.containsAll(conflicts), "sync conflicts", "must name selected paths");
        ProtocolValues.require(new HashSet<>(conflicts).size() == conflicts.size(), "sync conflicts", "must be unique");
        if (current != null) {
            ProtocolValues.require(next < paths.size(), "sync installation", "requires a remaining path");
            ProtocolValues.require(current.path().equals(paths.get(next)), "sync installation", "must match next path");
            ProtocolValues.require(current.baseline().commit().equals(commit), "sync baseline", "must match remote");
        }
    }

    PendingWorkspaceSync prepared(File file) {
        return new PendingWorkspaceSync(commit, paths, next, conflicts, file);
    }

    PendingWorkspaceSync advanced() {
        Objects.requireNonNull(current, "synchronization installation must be present");
        var found = new ArrayList<>(conflicts);
        if (current.conflict()) {
            found.add(current.path());
        }
        return new PendingWorkspaceSync(commit, paths, next + 1, found, null);
    }

    record File(
            String path,
            String expectedSha256,
            String text,
            RepositorySyncEntry blob,
            boolean delete,
            boolean install,
            RetainedFileBaseline baseline,
            boolean conflict) {
        File {
            RepositoryPaths.validate(path);
            if (expectedSha256 != null) {
                ProtocolValues.hex(expectedSha256, 64, "sync local digest");
            }
            Objects.requireNonNull(baseline, "synchronization baseline must be present");
            baseline.requirePath(path);
            if (text != null) {
                ProtocolValues.require(
                        text.getBytes(StandardCharsets.UTF_8).length <= 4 * 1024 * 1024,
                        "sync text",
                        "exceeds the merge output bound");
            }
            if (blob != null) {
                ProtocolValues.require(
                        blob.kind() == RepositorySyncEntry.Kind.FILE
                                && blob.path().equals(path),
                        "sync blob",
                        "must contain the selected regular file");
                ProtocolValues.require(blob.commit().equals(baseline.commit()), "sync blob", "must match the baseline");
            }
            int sources = (text == null ? 0 : 1) + (blob == null ? 0 : 1) + (delete ? 1 : 0);
            ProtocolValues.require(sources == (install ? 1 : 0), "sync installation", "must have one content source");
        }
    }
}
